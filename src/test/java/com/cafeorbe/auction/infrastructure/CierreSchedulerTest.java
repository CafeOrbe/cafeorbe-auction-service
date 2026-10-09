package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.auction.application.CierreDeSubastas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CierreSchedulerTest {

    @Test
    @DisplayName("cerrar(): cada pasada pide cerrar las subastas vencidas a la hora actual del reloj")
    void cierraConLaHoraDelReloj() {
        CierreDeSubastas cierre = mock(CierreDeSubastas.class);
        Instant ahora = Instant.parse("2026-10-08T12:00:00Z");

        new CierreScheduler(cierre, Clock.fixed(ahora, ZoneOffset.UTC)).cerrar();

        verify(cierre).cerrarVencidas(ahora);
    }
}
