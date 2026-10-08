package com.cafeorbe.auction.domain;

import java.time.Instant;

/** Resultado de intentar una puja sobre el agregado: aceptada (con la puja a registrar) o rechazada con motivo. */
public sealed interface ResultadoPuja {

    /**
     * @param extension extensión de tiempo que provocó la puja (HU-18), o {@code null} si no hubo
     */
    record Aceptada(Puja puja, Extension extension) implements ResultadoPuja {
    }

    record Rechazada(MotivoRechazo motivo, long montoIntentado) implements ResultadoPuja {
    }

    /** Extensión anti-sniping aplicada: cuánto se alargó, la nueva hora de fin y cuál es de las permitidas. */
    record Extension(int segundos, Instant horaFin, int numero, int maximo) {
    }
}
