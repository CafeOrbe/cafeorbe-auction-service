package com.cafeorbe.auction.api;

import com.cafeorbe.auction.domain.ReglasDePuja;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Cuerpos de las peticiones. Las reglas de negocio de fondo se validan en el dominio. */
public final class Solicitudes {

    public record CrearSubasta(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
            String nombre,
            @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
            String descripcion,
            @NotNull(message = "La fecha de inicio es obligatoria")
            Instant fechaInicio) {
    }

    /** Los límites coinciden con las columnas de la tabla subasta (hallazgo 1). */
    public record RegistrarFicha(
            @NotBlank(message = "La identificación es obligatoria")
            @Size(max = 100, message = "La identificación no puede superar 100 caracteres")
            String identificacion,
            @NotBlank(message = "El tipo de café es obligatorio")
            @Size(max = 100, message = "El tipo de café no puede superar 100 caracteres")
            String tipoCafe,
            @NotNull(message = "El peso debe ser mayor que cero") BigDecimal pesoKg,
            @NotNull(message = "La edad debe ser un número de meses (0 o más)") Integer edadMeses,
            @Size(max = 1000, message = "Las observaciones no pueden superar 1000 caracteres")
            String observaciones) {
    }

    /** Mismo mensaje que el frontend para un campo vacío o no positivo (hallazgo 8). */
    public record ConfigurarReglas(
            @NotNull(message = ReglasDePuja.MSG_VALORES_POSITIVOS) Integer duracionMinutos,
            @NotNull(message = ReglasDePuja.MSG_VALORES_POSITIVOS) Long precioBase,
            @NotNull(message = ReglasDePuja.MSG_VALORES_POSITIVOS) Long incrementoMinimo) {
    }

    private Solicitudes() {
    }
}
