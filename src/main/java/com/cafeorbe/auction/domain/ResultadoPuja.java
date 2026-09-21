package com.cafeorbe.auction.domain;

/** Resultado de intentar una puja sobre el agregado: aceptada (con la puja a registrar) o rechazada con motivo. */
public sealed interface ResultadoPuja {

    record Aceptada(Puja puja) implements ResultadoPuja {
    }

    record Rechazada(MotivoRechazo motivo, long montoIntentado) implements ResultadoPuja {
    }
}
