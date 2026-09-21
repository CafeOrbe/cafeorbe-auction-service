package com.cafeorbe.auction.domain;

/**
 * Objeto de valor con las reglas de puja de una subasta (HU-10).
 * Sprint 2 agregará aquí la ventana anti-sniping y el máximo de extensiones.
 */
public record ReglasDePuja(int duracionMinutos, long precioBase, long incrementoMinimo) {

    public ReglasDePuja {
        if (duracionMinutos <= 0 || precioBase <= 0 || incrementoMinimo <= 0) {
            throw ReglaDeNegocioException.validacion("Los valores deben ser mayores que cero");
        }
    }
}
