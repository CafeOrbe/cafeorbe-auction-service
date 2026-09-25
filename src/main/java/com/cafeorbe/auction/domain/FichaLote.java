package com.cafeorbe.auction.domain;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/** Ficha técnica del lote (HU-09): identificación, tipo de café, peso, edad y observaciones. */
@Embeddable
public record FichaLote(String identificacion, String tipoCafe, BigDecimal pesoKg, Integer edadMeses, String observaciones) {

    /** Límites de las columnas de la tabla subasta (V1): superarlos daba HTTP 500 (hallazgo 1). */
    public static final int MAX_TEXTO_CORTO = 100;
    public static final int MAX_OBSERVACIONES = 1000;

    public FichaLote {
        if (vacio(identificacion)) {
            throw ReglaDeNegocioException.validacion("identificacion", "La identificación es obligatoria");
        }
        if (vacio(tipoCafe)) {
            throw ReglaDeNegocioException.validacion("tipoCafe", "El tipo de café es obligatorio");
        }
        if (pesoKg == null || pesoKg.signum() <= 0) {
            throw ReglaDeNegocioException.validacion("pesoKg", "El peso debe ser mayor que cero");
        }
        if (edadMeses == null || edadMeses < 0) {
            throw ReglaDeNegocioException.validacion("edadMeses", "La edad debe ser un número de meses (0 o más)");
        }
        identificacion = identificacion.trim();
        tipoCafe = tipoCafe.trim();
        observaciones = vacio(observaciones) ? null : observaciones.trim();
        if (identificacion.length() > MAX_TEXTO_CORTO) {
            throw ReglaDeNegocioException.validacion("identificacion", "La identificación no puede superar 100 caracteres");
        }
        if (tipoCafe.length() > MAX_TEXTO_CORTO) {
            throw ReglaDeNegocioException.validacion("tipoCafe", "El tipo de café no puede superar 100 caracteres");
        }
        if (observaciones != null && observaciones.length() > MAX_OBSERVACIONES) {
            throw ReglaDeNegocioException.validacion("observaciones", "Las observaciones no pueden superar 1000 caracteres");
        }
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
