package com.cafeorbe.auction.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDespachadorTest {

    private final OutboxPublisher publicador = mock(OutboxPublisher.class);
    private final OutboxDespachador despachador = new OutboxDespachador(publicador);

    @AfterEach
    void limpiar() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        despachador.cerrar();
    }

    @Test
    @DisplayName("REN-01 · sin transacción en curso publica de inmediato, sin esperar al scheduler")
    void sinTransaccionPublicaYa() {
        despachador.alConfirmar();

        verify(publicador, timeout(2000)).publicarPendientes();
    }

    @Test
    @DisplayName("REN-01 · dentro de una transacción espera al commit para publicar")
    void esperaAlCommit() {
        TransactionSynchronizationManager.initSynchronization();

        despachador.alConfirmar();
        verify(publicador, after(200).never()).publicarPendientes();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(publicador, timeout(2000)).publicarPendientes();
    }

    @Test
    @DisplayName("REN-01 · si la transacción se revierte no publica (el evento no existe)")
    void sinCommitNoPublica() {
        TransactionSynchronizationManager.initSynchronization();

        despachador.alConfirmar();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(publicador, after(300).never()).publicarPendientes();
    }

    @Test
    @DisplayName("REN-01 · los avisos que llegan mientras hay una publicación en cola se agrupan en una sola")
    void agrupaLosAvisos() throws InterruptedException {
        CountDownLatch enCurso = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        when(publicador.publicarPendientes()).thenAnswer(i -> {
            enCurso.countDown();
            soltar.await(2, TimeUnit.SECONDS);
            return 0;
        });

        despachador.despertar();
        assertThat(enCurso.await(2, TimeUnit.SECONDS)).isTrue();
        // Mientras publica, llegan tres avisos: basta una pasada más para llevarse todo.
        despachador.despertar();
        despachador.despertar();
        despachador.despertar();
        soltar.countDown();

        verify(publicador, after(500).times(2)).publicarPendientes();
    }

    @Test
    @DisplayName("REN-01 · si la publicación inmediata falla, el error no se propaga (la hará el scheduler)")
    void unFalloNoSePropaga() {
        when(publicador.publicarPendientes()).thenThrow(new IllegalStateException("broker caído")).thenReturn(1);

        despachador.despertar();
        verify(publicador, timeout(2000)).publicarPendientes();
        despachador.despertar();

        verify(publicador, timeout(2000).times(2)).publicarPendientes();
    }

    @Test
    @DisplayName("REN-01 · el escritor avisa al despachador al guardar el evento")
    void elEscritorAvisa() {
        OutboxWriter escritor = new OutboxWriter(mock(OutboxRepository.class), new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC());
        escritor.setDespachador(despachador);

        escritor.registrar("puja.aceptada", Map.of("monto", 300));

        verify(publicador, timeout(2000)).publicarPendientes();
    }
}
