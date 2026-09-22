package com.cafeorbe.auction.api;

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

    public record RegistrarFicha(
            @NotBlank(message = "La identificación es obligatoria") String identificacion,
            @NotBlank(message = "El tipo de café es obligatorio") String tipoCafe,
            @NotNull(message = "El peso es obligatorio") BigDecimal pesoKg,
            @NotNull(message = "La edad es obligatoria") Integer edadMeses,
            String observaciones) {
    }

    public record ConfigurarReglas(
            @NotNull(message = "Todos los campos son obligatorios") Integer duracionMinutos,
            @NotNull(message = "Todos los campos son obligatorios") Long precioBase,
            @NotNull(message = "Todos los campos son obligatorios") Long incrementoMinimo) {
    }

    private Solicitudes() {
    }
}
