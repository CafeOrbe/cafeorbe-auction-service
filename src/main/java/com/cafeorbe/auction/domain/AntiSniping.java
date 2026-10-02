package com.cafeorbe.auction.domain;

/**
 * Regla anti-sniping de una subasta (HU-18): si llega una puja válida cuando faltan {@code ventanaSegundos}
 * o menos, el tiempo se extiende esa misma cantidad, como máximo {@code maxExtensiones} veces.
 */
public record AntiSniping(int ventanaSegundos, int maxExtensiones) {

    public static final AntiSniping DESACTIVADO = new AntiSniping(0, 0);

    public AntiSniping {
        if (ventanaSegundos < 0 || maxExtensiones < 0) {
            throw new IllegalArgumentException("La ventana y el máximo de extensiones no pueden ser negativos");
        }
    }
}
