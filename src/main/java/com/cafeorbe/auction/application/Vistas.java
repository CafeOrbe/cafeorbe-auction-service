package com.cafeorbe.auction.application;

import com.cafeorbe.auction.domain.EstadoSubasta;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Modelos de lectura (lado de consultas): lo que ven el lobby, la sala y el panel del Subastador. */
public final class Vistas {

    public record Resumen(UUID id, String nombre, EstadoSubasta estado, Instant fechaInicio,
                          String subastadorNombre, Long precioActual, int cantidadPujas) {
    }

    public record FichaVista(String identificacion, String tipoCafe, BigDecimal pesoKg, Integer edadMeses,
                             String observaciones) {
    }

    public record ReglasVista(int duracionMinutos, long precioBase, long incrementoMinimo, int version) {
    }

    public record LiderVista(UUID id, String nombre) {
    }

    public record PujaVista(UUID id, UUID usuarioId, String usuarioNombre, long monto, Instant creadaEn) {
    }

    public record Detalle(UUID id, String nombre, String descripcion, EstadoSubasta estado, Instant fechaInicio,
                          UUID subastadorId, String subastadorNombre, FichaVista ficha, ReglasVista reglas,
                          Instant horaInicio, Instant horaFin, Long precioActual, Long siguienteMinimo,
                          LiderVista lider, int cantidadPujas, List<PujaVista> ultimasPujas) {
    }

    private Vistas() {
    }
}
