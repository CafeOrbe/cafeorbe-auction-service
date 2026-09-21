package com.cafeorbe.auction.domain;

/** Motivos tipificados por los que se rechaza una puja. El mensaje es el que ve el usuario. */
public enum MotivoRechazo {
    SUBASTA_NO_EN_CURSO("La subasta no está en curso"),
    SUBASTA_FINALIZADA("La subasta ya finalizó"),
    YA_ERES_LIDER("Vas ganando"),
    INCREMENTO_MINIMO("No cumple el incremento mínimo"),
    SALDO_INSUFICIENTE("Orbes insuficientes"),
    SALDO_NO_DISPONIBLE("No se pudo verificar tu saldo de Orbes, intenta de nuevo");

    private final String mensaje;

    MotivoRechazo(String mensaje) {
        this.mensaje = mensaje;
    }

    public String mensaje() {
        return mensaje;
    }
}
