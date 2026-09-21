package com.cafeorbe.auction.domain;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/** Ficha técnica del lote (HU-09): identificación, raza, peso, edad y observaciones. */
@Embeddable
public record FichaLote(String identificacion, String raza, BigDecimal pesoKg, Integer edadMeses, String observaciones) {

    public FichaLote {
        if (vacio(identificacion) || vacio(raza) || pesoKg == null || edadMeses == null) {
            throw ReglaDeNegocioException.validacion("Identificación, raza, peso y edad son obligatorios");
        }
        if (pesoKg.signum() <= 0) {
            throw ReglaDeNegocioException.validacion("El peso debe ser mayor que cero");
        }
        if (edadMeses < 0) {
            throw ReglaDeNegocioException.validacion("La edad no puede ser negativa");
        }
        identificacion = identificacion.trim();
        raza = raza.trim();
        observaciones = vacio(observaciones) ? null : observaciones.trim();
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
