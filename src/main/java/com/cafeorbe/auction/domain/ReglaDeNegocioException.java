package com.cafeorbe.auction.domain;

/** Violación de una regla del dominio. El tipo decide el código HTTP en la capa api. */
public class ReglaDeNegocioException extends RuntimeException {

    public enum Tipo {
        VALIDACION,
        PROHIBIDO,
        NO_ENCONTRADO,
        CONFLICTO
    }

    private final Tipo tipo;

    private ReglaDeNegocioException(Tipo tipo, String mensaje) {
        super(mensaje);
        this.tipo = tipo;
    }

    public static ReglaDeNegocioException validacion(String mensaje) {
        return new ReglaDeNegocioException(Tipo.VALIDACION, mensaje);
    }

    public static ReglaDeNegocioException prohibido(String mensaje) {
        return new ReglaDeNegocioException(Tipo.PROHIBIDO, mensaje);
    }

    public static ReglaDeNegocioException noEncontrado(String mensaje) {
        return new ReglaDeNegocioException(Tipo.NO_ENCONTRADO, mensaje);
    }

    public static ReglaDeNegocioException conflicto(String mensaje) {
        return new ReglaDeNegocioException(Tipo.CONFLICTO, mensaje);
    }

    public Tipo getTipo() {
        return tipo;
    }
}
