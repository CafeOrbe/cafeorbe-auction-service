package com.cafeorbe.auction.domain;

/** Máquina de estados: Programada → En curso → Finalizada o Desierta. */
public enum EstadoSubasta {
    PROGRAMADA,
    EN_CURSO,
    FINALIZADA,
    DESIERTA;

    public boolean terminada() {
        return this == FINALIZADA || this == DESIERTA;
    }
}
