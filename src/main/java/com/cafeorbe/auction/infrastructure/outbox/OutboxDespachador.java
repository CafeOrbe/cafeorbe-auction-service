package com.cafeorbe.auction.infrastructure.outbox;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REN-01: publica la outbox en cuanto confirma la transacción que escribió un evento, sin esperar la siguiente pasada
 * del {@link OutboxScheduler}. Con el scheduler solo, cada puja aceptada esperaba hasta un intervalo completo antes de
 * salir al broker, y eso se sumaba entero al p95 de la sala.
 * <p>
 * Es seguro publicar desde aquí y desde el scheduler a la vez: {@link OutboxRepository#pendientes} bloquea las filas
 * y espera (sin SKIP LOCKED), así que los publicadores se turnan, los eventos salen en orden de id y ninguno sale dos
 * veces. El scheduler sigue como respaldo: si esta publicación falla, el evento queda pendiente y sale en su pasada.
 * <p>
 * Los avisos se agrupan: si ya hay una publicación en cola, otro aviso no agrega otra, porque esa pasada se llevará
 * todo lo pendiente.
 */
@Component
@ConditionalOnProperty(name = "cafeorbe.outbox.scheduler", havingValue = "true", matchIfMissing = true)
public class OutboxDespachador {

    private static final Logger log = LoggerFactory.getLogger(OutboxDespachador.class);

    private final OutboxPublisher publicador;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(tarea -> {
        Thread t = new Thread(tarea, "outbox-inmediato");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean enCola = new AtomicBoolean();

    public OutboxDespachador(OutboxPublisher publicador) {
        this.publicador = publicador;
    }

    /** Pide publicar cuando confirme la transacción en curso (o ya, si no hay transacción). */
    public void alConfirmar() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            despertar();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                despertar();
            }
        });
    }

    void despertar() {
        if (!enCola.compareAndSet(false, true)) {
            return;
        }
        hilo.execute(() -> {
            enCola.set(false);
            try {
                publicador.publicarPendientes();
            } catch (RuntimeException e) {
                // El evento sigue pendiente: lo publica la siguiente pasada del scheduler.
                log.warn("La publicación inmediata de la outbox falló; la hará el scheduler: {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    void cerrar() {
        hilo.shutdown();
    }
}
