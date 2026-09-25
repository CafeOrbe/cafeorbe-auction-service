package com.cafeorbe.auction.domain;

import java.util.Optional;

/** Violación de una regla del dominio. El tipo decide el código HTTP en la capa api. */
public class ReglaDeNegocioException extends RuntimeException {

    public enum Tipo {
        VALIDACION,
        PROHIBIDO,
        NO_ENCONTRADO,
        CONFLICTO
    }

    private final Tipo tipo;
    /** Campo de la solicitud al que se refiere el error, para mostrarlo junto a él (hallazgo 3). */
    private final String campo;

    private ReglaDeNegocioException(Tipo tipo, String campo, String mensaje) {
        super(mensaje);
        this.tipo = tipo;
        this.campo = campo;
    }

    public static ReglaDeNegocioException validacion(String mensaje) {
        return new ReglaDeNegocioException(Tipo.VALIDACION, null, mensaje);
    }

    /** Error de validación de un campo concreto: la api lo devuelve también en {@code campos}. */
    public static ReglaDeNegocioException validacion(String campo, String mensaje) {
        return new ReglaDeNegocioException(Tipo.VALIDACION, campo, mensaje);
    }

    public static ReglaDeNegocioException prohibido(String mensaje) {
        return new ReglaDeNegocioException(Tipo.PROHIBIDO, null, mensaje);
    }

    public static ReglaDeNegocioException noEncontrado(String mensaje) {
        return new ReglaDeNegocioException(Tipo.NO_ENCONTRADO, null, mensaje);
    }

    public static ReglaDeNegocioException conflicto(String mensaje) {
        return new ReglaDeNegocioException(Tipo.CONFLICTO, null, mensaje);
    }

    public Tipo getTipo() {
        return tipo;
    }

    public Optional<String> getCampo() {
        return Optional.ofNullable(campo);
    }
}
