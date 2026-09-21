package com.cafeorbe.auction.api;

import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import com.cafeorbe.contracts.Rol;

import java.util.UUID;

/** Identidad del usuario que hace la petición, tomada de las cabeceras que inyecta el api-gateway. */
public record UsuarioActual(UUID id, String nombre, Rol rol) {

    public void exigirRol(Rol requerido) {
        if (rol != requerido) {
            throw ReglaDeNegocioException.prohibido("No autorizado");
        }
    }
}
