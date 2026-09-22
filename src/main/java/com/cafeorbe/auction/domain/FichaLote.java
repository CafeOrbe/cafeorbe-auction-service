package com.cafeorbe.auction.domain;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/** Ficha técnica del lote (HU-09): identificación, tipo de café, peso, edad y observaciones. */
@Embeddable
public record FichaLote(String identificacion, String tipoCafe, BigDecimal pesoKg, Integer edadMeses, String observaciones) {

    public FichaLote {
        if (vacio(identificacion) || vacio(tipoCafe) || pesoKg == null || edadMeses == null) {
            throw ReglaDeNegocioException.validacion("Identificación, tipo de café, peso y edad son obligatorios");
        }
        if (pesoKg.signum() <= 0) {
            throw ReglaDeNegocioException.validacion("El peso debe ser mayor que cero");
        }
        if (edadMeses < 0) {
            throw ReglaDeNegocioException.validacion("La edad no puede ser negativa");
        }
        identificacion = identificacion.trim();
        tipoCafe = tipoCafe.trim();
        observaciones = vacio(observaciones) ? null : observaciones.trim();
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
