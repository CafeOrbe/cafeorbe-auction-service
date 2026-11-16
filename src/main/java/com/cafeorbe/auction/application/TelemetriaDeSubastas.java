package com.cafeorbe.auction.application;

import com.cafeorbe.auction.domain.Subasta;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * OBS-01 (tablero de negocio): telemetría del ciclo de vida de las subastas.
 * <ul>
 *   <li>Métrica {@code cafeorbe_auction_subastas_total{evento, resultado}}: {@code creada}, {@code iniciada} y
 *       {@code cerrada} (con {@code resultado} {@code con_ganador} o {@code desierta}). Se cuenta solo si la
 *       transacción quedó confirmada, así una operación revertida no infla el tablero.</li>
 *   <li>Una línea de log estructurada por evento ({@code evento=subasta_creada}, ...), con el correlationId del MDC,
 *       para seguirla en Log Analytics. El correlationId no se agrega aquí: lo escribe el MDC y repetirlo hace
 *       fallar al escritor JSON.</li>
 * </ul>
 */
@Component
public class TelemetriaDeSubastas {

    private static final Logger log = LoggerFactory.getLogger(TelemetriaDeSubastas.class);
    private static final String METRICA = "cafeorbe.auction.subastas";

    private final MeterRegistry metricas;

    public TelemetriaDeSubastas(MeterRegistry metricas) {
        this.metricas = metricas;
    }

    public void creada(Subasta subasta) {
        registrar("creada", "-", subasta);
    }

    public void iniciada(Subasta subasta) {
        registrar("iniciada", "-", subasta);
    }

    public void cerrada(Subasta subasta, boolean conGanador) {
        registrar("cerrada", conGanador ? "con_ganador" : "desierta", subasta);
    }

    private void registrar(String evento, String resultado, Subasta subasta) {
        log.atInfo().addKeyValue("evento", "subasta_" + evento).addKeyValue("resultado", resultado)
                .addKeyValue("subastaId", subasta.getId()).addKeyValue("subastador", subasta.getSubastadorId())
                .addKeyValue("pujas", subasta.getCantidadPujas())
                .log("Subasta {} {}{}", subasta.getId(), evento, "-".equals(resultado) ? "" : " (" + resultado + ")");
        Runnable contar = () -> metricas.counter(METRICA, "evento", evento, "resultado", resultado).increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    contar.run();
                }
            });
        } else {
            contar.run();
        }
    }
}
