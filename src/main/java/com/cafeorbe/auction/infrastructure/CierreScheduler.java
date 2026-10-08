package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.auction.application.CierreDeSubastas;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Programador del cierre automático (HU-19). Se puede desactivar en las pruebas. */
@Component
@ConditionalOnProperty(name = "cafeorbe.cierre.scheduler", havingValue = "true", matchIfMissing = true)
public class CierreScheduler {

    private final CierreDeSubastas cierre;
    private final Clock reloj;

    public CierreScheduler(CierreDeSubastas cierre, Clock reloj) {
        this.cierre = cierre;
        this.reloj = reloj;
    }

    @Scheduled(fixedDelayString = "${cafeorbe.cierre.intervalo-ms:1000}")
    public void cerrar() {
        cierre.cerrarVencidas(reloj.instant());
    }
}
