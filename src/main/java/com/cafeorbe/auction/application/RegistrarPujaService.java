package com.cafeorbe.auction.application;

import com.cafeorbe.auction.api.UsuarioActual;
import com.cafeorbe.auction.domain.EstadoSubasta;
import com.cafeorbe.auction.domain.MotivoRechazo;
import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import com.cafeorbe.auction.domain.ResultadoPuja;
import com.cafeorbe.auction.domain.Subasta;
import com.cafeorbe.auction.infrastructure.PujaRepository;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import com.cafeorbe.auction.infrastructure.WalletClient;
import com.cafeorbe.auction.infrastructure.outbox.OutboxWriter;
import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.Rol;
import com.cafeorbe.contracts.eventos.PujaAceptada;
import com.cafeorbe.contracts.eventos.PujaRechazada;
import com.cafeorbe.contracts.eventos.TiempoExtendido;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.UUID;

/** HU-13 y HU-14: valida y registra una puja. */
@Service
public class RegistrarPujaService {

    /**
     * OBS-01 / REN-01: buckets fijos en los umbrales del escenario (p95 ≤ 800 ms, p99 ≤ 1,5 s). Un histograma
     * completo son ~70 series por réplica y se come el plan gratuito de Grafana Cloud.
     */
    static final Duration[] UMBRALES = {Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200),
            Duration.ofMillis(400), Duration.ofMillis(800), Duration.ofMillis(1500), Duration.ofSeconds(3)};

    /**
     * Resultado del dominio más el estado de la subasta tras procesar la puja.
     *
     * @param porEvento {@code false} cuando el rechazo se decidió sin transacción y sin publicar PujaRechazada
     *                  (REN-01): quien reenvió la puja debe avisar directamente a quien pujó
     */
    public record Registro(ResultadoPuja resultado, long siguienteMinimo, int cantidadPujas, boolean porEvento) {
        public Registro(ResultadoPuja resultado, long siguienteMinimo, int cantidadPujas) {
            this(resultado, siguienteMinimo, cantidadPujas, true);
        }
    }

    private final SubastaRepository subastas;
    private final PujaRepository pujas;
    private final WalletClient wallet;
    private final OutboxWriter outbox;
    private final Clock reloj;
    private final TransactionTemplate transaccion;

    private final MeterRegistry metricas;
    private final Timer duracion;

    public RegistrarPujaService(SubastaRepository subastas, PujaRepository pujas, WalletClient wallet,
                                OutboxWriter outbox, Clock reloj, PlatformTransactionManager gestor,
                                MeterRegistry metricas) {
        this.subastas = subastas;
        this.pujas = pujas;
        this.wallet = wallet;
        this.outbox = outbox;
        this.reloj = reloj;
        this.transaccion = new TransactionTemplate(gestor);
        this.metricas = metricas;
        // OBS-01 / REN-01: tiempo de registro de una puja, con p95 y p99 para el tablero técnico.
        this.duracion = Timer.builder("cafeorbe.auction.pujas.registro")
                .serviceLevelObjectives(UMBRALES).register(metricas);
    }

    public Registro registrar(UUID subastaId, UsuarioActual usuario, long monto) {
        usuario.exigirRol(Rol.COMPRADOR);
        Registro registro = duracion.record(() -> procesar(subastaId, usuario, monto));
        contar(registro);
        return registro;
    }

    private Registro procesar(UUID subastaId, UsuarioActual usuario, long monto) {
        // REN-01: con 20 pujas por segundo sobre el mismo precio, la mayoría ya llega superada. El precio solo
        // sube, así que si una lectura sin bloqueo ya dice que el monto no alcanza, con el bloqueo tampoco
        // alcanzaría: se rechaza sin abrir la transacción de escritura, sin consultar a wallet y sin evento.
        Registro temprano = rechazoTemprano(subastaId, monto);
        if (temprano != null) {
            return temprano;
        }

        // El saldo se consulta antes de bloquear la subasta para no sostener el bloqueo durante una llamada HTTP.
        OptionalLong saldo = wallet.saldoDe(usuario.id());

        return transaccion.execute(estado -> {
            Subasta subasta = subastas.findByIdForUpdate(subastaId)
                    .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("La subasta no existe"));

            ResultadoPuja resultado = subasta.pujar(usuario.id(), usuario.nombre(), monto, saldo, reloj.instant());
            long siguiente = subasta.reglas().isPresent() ? subasta.siguienteMinimo() : 0;

            switch (resultado) {
                case ResultadoPuja.Aceptada aceptada -> {
                    var puja = aceptada.puja();
                    pujas.save(puja);
                    subastas.save(subasta);
                    outbox.registrar(Eventos.PUJA_ACEPTADA, new PujaAceptada(subastaId, puja.getId(),
                            usuario.id(), usuario.nombre(), puja.getMonto(), subasta.getCantidadPujas(), siguiente,
                            puja.getCreadaEn()));
                    // HU-18: la extensión queda registrada como evento, después del de la puja que la provocó.
                    var extension = aceptada.extension();
                    if (extension != null) {
                        outbox.registrar(Eventos.TIEMPO_EXTENDIDO, new TiempoExtendido(subastaId, extension.segundos(),
                                extension.horaFin(), extension.numero(), extension.maximo()));
                    }
                }
                case ResultadoPuja.Rechazada rechazada -> outbox.registrar(Eventos.PUJA_RECHAZADA,
                        new PujaRechazada(subastaId, usuario.id(), rechazada.montoIntentado(),
                                rechazada.motivo().name(), rechazada.motivo().mensaje()));
            }
            return new Registro(resultado, siguiente, subasta.getCantidadPujas());
        });
    }

    private Registro rechazoTemprano(UUID subastaId, long monto) {
        Subasta vistazo = subastas.findById(subastaId).orElse(null);
        if (vistazo == null || vistazo.getEstado() != EstadoSubasta.EN_CURSO || vistazo.reglas().isEmpty()) {
            return null;
        }
        long minimo = vistazo.siguienteMinimo();
        if (monto >= minimo) {
            return null;
        }
        var rechazo = new ResultadoPuja.Rechazada(MotivoRechazo.INCREMENTO_MINIMO, monto);
        return new Registro(rechazo, minimo, vistazo.getCantidadPujas(), false);
    }

    private void contar(Registro registro) {
        switch (registro.resultado()) {
            case ResultadoPuja.Aceptada a -> metricas.counter("cafeorbe.auction.pujas", "resultado", "aceptada",
                    "motivo", "ninguno").increment();
            case ResultadoPuja.Rechazada r -> metricas.counter("cafeorbe.auction.pujas", "resultado", "rechazada",
                    "motivo", r.motivo().name()).increment();
        }
    }
}
