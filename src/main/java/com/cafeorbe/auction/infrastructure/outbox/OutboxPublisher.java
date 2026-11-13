package com.cafeorbe.auction.infrastructure.outbox;

import com.cafeorbe.contracts.Eventos;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Publica en el broker los eventos pendientes de la outbox. Entrega al-menos-una-vez:
 * si el broker falla, el evento sigue pendiente y se reintenta en la siguiente pasada.
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

    public OutboxPublisher(OutboxRepository outbox, RabbitTemplate rabbit, Clock reloj) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.reloj = reloj;
    }

    /** OBS-01: eventos que esperan en la outbox; si crece, el broker o el publicador están atrasados. */
    @Autowired
    void registrarMetricas(MeterRegistry metricas) {
        Gauge.builder("cafeorbe.outbox.pendientes", outbox, OutboxRepository::countByPublicadoEnIsNull)
                .register(metricas);
    }

    /** @return cantidad de eventos publicados en esta pasada */
    @Transactional
    public int publicarPendientes() {
        int publicados = 0;
        for (OutboxEvento evento : outbox.pendientes(PageRequest.of(0, TAMANO_LOTE))) {
            try {
                Message mensaje = MessageBuilder
                        .withBody(evento.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setMessageId(evento.getEventId().toString())
                        // REN-01: número de secuencia del evento; el navegador descarta lo repetido o atrasado.
                        .setHeader(CABECERA_SECUENCIA, evento.getId())
                        // OBS-01: la puja se sigue por su correlationId también a través del broker.
                        .setHeader(CABECERA_CORRELACION, evento.getCorrelationId())
                        .build();
                rabbit.send(Eventos.EXCHANGE, evento.getTipo(), mensaje);
                evento.marcarPublicado(reloj.instant());
                publicados++;
                log.atInfo().addKeyValue("correlationId", evento.getCorrelationId())
                        .addKeyValue("secuencia", evento.getId()).addKeyValue("evento", evento.getTipo())
                        .log("Evento {} publicado en el broker", evento.getTipo());
            } catch (RuntimeException e) {
                // Se conserva el orden: no se publican eventos posteriores si este falló.
                log.warn("No se pudo publicar el evento {} ({}); se reintentará: {}", evento.getEventId(),
                        evento.getTipo(), e.getMessage());
                break;
            }
        }
        return publicados;
    }
}
