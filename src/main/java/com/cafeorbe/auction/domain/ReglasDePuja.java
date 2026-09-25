package com.cafeorbe.auction.domain;

/**
 * Objeto de valor con las reglas de puja de una subasta (HU-10).
 * Sprint 2 agregará aquí la ventana anti-sniping y el máximo de extensiones.
 */
public record ReglasDePuja(int duracionMinutos, long precioBase, long incrementoMinimo) {

    public static final String MSG_VALORES_POSITIVOS = "Los valores deben ser mayores que cero";

    public ReglasDePuja {
        // Se revisan en el orden del formulario para señalar el primer campo inválido (hallazgo 3).
        if (duracionMinutos <= 0) {
            throw ReglaDeNegocioException.validacion("duracionMinutos", MSG_VALORES_POSITIVOS);
        }
        if (precioBase <= 0) {
            throw ReglaDeNegocioException.validacion("precioBase", MSG_VALORES_POSITIVOS);
        }
        if (incrementoMinimo <= 0) {
            throw ReglaDeNegocioException.validacion("incrementoMinimo", MSG_VALORES_POSITIVOS);
        }
    }
}
