package com.cafeorbe.auction.infrastructure.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El scheduler es lo único que mueve los eventos de la outbox hacia RabbitMQ. Si deja de correr, las
 * pujas aceptadas se quedan en la base y ningún otro servicio se entera: por eso se prueba que cada
 * pasada publica, y que un fallo de RabbitMQ no deja el scheduler muerto.
 */
class OutboxSchedulerTest {

    @Test
    @DisplayName("publicar(): delega en el publicador, que es quien decide cuántos eventos salen")
    void delegaEnElPublicador() {
        OutboxPublisher publicador = mock(OutboxPublisher.class);
        when(publicador.publicarPendientes()).thenReturn(0);

        new OutboxScheduler(publicador).publicar();

        verify(publicador).publicarPendientes();
    }

    @Test
    @DisplayName("publicar(): si RabbitMQ falla en una pasada, la siguiente se sigue intentando")
    void unaPasadaFallidaNoDejaElSchedulerMuerto() {
        OutboxPublisher publicador = mock(OutboxPublisher.class);
        when(publicador.publicarPendientes())
                .thenThrow(new RuntimeException("RabbitMQ no disponible"))
                .thenReturn(3);
        var scheduler = new OutboxScheduler(publicador);

        assertThatThrownBy(scheduler::publicar).isInstanceOf(RuntimeException.class);
        assertThatCode(scheduler::publicar).doesNotThrowAnyException();

        verify(publicador, times(2)).publicarPendientes();
    }
}
