package com.cafeorbe.auction.infrastructure.outbox;

import com.cafeorbe.auction.seguridad.FiltroDeCorrelacion;
import com.cafeorbe.contracts.Eventos;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica en el broker los eventos pendientes de la outbox. Entrega al-menos-una-vez:
 * si el broker falla, el evento sigue pendiente y se reintenta en la siguiente pasada.
 * <p>
 * Un evento solo se marca como publicado cuando RabbitMQ confirma que lo recibió (publisher confirm con ack) y
 * que lo pudo enrutar (sin devolución por {@code mandatory}). Si el broker lo rechaza (nack), lo devuelve o no
 * confirma a tiempo, el evento queda pendiente. Lo que se reintente puede llegar dos veces: los consumidores
 * descartan repetidos por {@code messageId} (wallet guarda el eventId procesado), así que no hay cobros dobles.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int TAMANO_LOTE = 50;
    public static final String CABECERA_SECUENCIA = "x-secuencia";
    public static final String CABECERA_CORRELACION = "x-correlation-id";

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final Clock reloj;
    private final long esperaConfirmacionMs;

    public OutboxPublisher(OutboxRepository outbox, RabbitTemplate rabbit, Clock reloj,
                           @Value("${cafeorbe.outbox.confirmacion-ms:5000}") long esperaConfirmacionMs) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.reloj = reloj;
        this.esperaConfirmacionMs = esperaConfirmacionMs;
    }

    /** Evento enviado al broker que espera su confirmación. */
    private record Enviado(OutboxEvento evento, CorrelationData confirmacion) {
    }

    /** OBS-01: eventos que esperan en la outbox; si crece, el broker o el publicador están atrasados. */
    @Autowired
    void registrarMetricas(MeterRegistry metricas) {
        Gauge.builder("cafeorbe.outbox.pendientes", outbox, OutboxRepository::countByPublicadoEnIsNull)
                .register(metricas);
    }

    /** @return cantidad de eventos publicados (confirmados por el broker) en esta pasada */
    @Transactional
    public int publicarPendientes() {
        // Se envía el lote completo y luego se esperan las confirmaciones: esperar una por una sumaría un viaje
        // de ida y vuelta al broker por evento y alargaría la latencia de las pujas (REN-01).
        List<Enviado> enviados = new ArrayList<>();
        for (OutboxEvento evento : outbox.pendientes(PageRequest.of(0, TAMANO_LOTE))) {
            try {
                CorrelationData confirmacion = new CorrelationData(evento.getEventId().toString());
                rabbit.send(Eventos.EXCHANGE, evento.getTipo(), mensaje(evento), confirmacion);
                enviados.add(new Enviado(evento, confirmacion));
            } catch (RuntimeException e) {
                // Se conserva el orden: no se envían eventos posteriores si este falló.
                log.warn("No se pudo publicar el evento {} ({}); se reintentará: {}", evento.getEventId(),
                        evento.getTipo(), e.getMessage());
                break;
            }
        }

        int publicados = 0;
        for (Enviado enviado : enviados) {
            OutboxEvento evento = enviado.evento();
            String problema = problemaDeConfirmacion(enviado.confirmacion());
            if (problema != null) {
                // Se conserva el orden: lo posterior a un evento no confirmado también queda pendiente.
                log.warn("El broker no confirmó el evento {} ({}): {}; se reintentará", evento.getEventId(),
                        evento.getTipo(), problema);
                break;
            }
            evento.marcarPublicado(reloj.instant());
            publicados++;
            registrarPublicado(evento);
        }
        return publicados;
    }

    private static Message mensaje(OutboxEvento evento) {
        return MessageBuilder
                .withBody(evento.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(evento.getEventId().toString())
                // REN-01: número de secuencia del evento; el navegador descarta lo repetido o atrasado.
                .setHeader(CABECERA_SECUENCIA, evento.getId())
                // OBS-01: la puja se sigue por su correlationId también a través del broker.
                .setHeader(CABECERA_CORRELACION, evento.getCorrelationId())
                .build();
    }

    /** @return {@code null} si el broker confirmó y enrutó el evento; si no, el motivo. */
    private String problemaDeConfirmacion(CorrelationData confirmacion) {
        try {
            CorrelationData.Confirm respuesta = confirmacion.getFuture().get(esperaConfirmacionMs, TimeUnit.MILLISECONDS);
            if (!respuesta.isAck()) {
                return "rechazado (nack: " + respuesta.getReason() + ")";
            }
            if (confirmacion.getReturned() != null) {
                return "devuelto sin enrutar (" + confirmacion.getReturned().getReplyText() + ")";
            }
            return null;
        } catch (TimeoutException e) {
            return "sin confirmación en " + esperaConfirmacionMs + " ms";
        } catch (ExecutionException e) {
            return "error al confirmar: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrumpido esperando la confirmación";
        }
    }

    /**
     * OBS-01: el log lleva el correlationId del evento. Va por el MDC, no con addKeyValue: si el hilo ya tuviera
     * uno en el MDC (el cierre automático comparte el programador y usa {@code cierre-<id>}), el campo saldría
     * repetido, el escritor JSON fallaría y la línea se perdería. Se restaura el valor anterior al terminar.
     */
    private static void registrarPublicado(OutboxEvento evento) {
        String anterior = MDC.get(FiltroDeCorrelacion.MDC_CLAVE);
        if (evento.getCorrelationId() != null) {
            MDC.put(FiltroDeCorrelacion.MDC_CLAVE, evento.getCorrelationId());
        }
        try {
            log.atInfo().addKeyValue("secuencia", evento.getId()).addKeyValue("evento", evento.getTipo())
                    .log("Evento {} publicado en el broker", evento.getTipo());
        } finally {
            if (anterior == null) {
                MDC.remove(FiltroDeCorrelacion.MDC_CLAVE);
            } else {
                MDC.put(FiltroDeCorrelacion.MDC_CLAVE, anterior);
            }
        }
    }
}
