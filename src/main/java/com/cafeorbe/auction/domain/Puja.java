package com.cafeorbe.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Puja aceptada: queda registrada con usuario, monto y hora (HU-14). Inmutable. */
@Entity
@Table(name = "puja")
public class Puja {

    @Id
    private UUID id;

    @Column(name = "subasta_id", nullable = false, updatable = false)
    private UUID subastaId;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "usuario_nombre", nullable = false, updatable = false, length = 50)
    private String usuarioNombre;

    @Column(nullable = false, updatable = false)
    private long monto;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    protected Puja() {
    }

    Puja(UUID subastaId, UUID usuarioId, String usuarioNombre, long monto, Instant creadaEn) {
        this.id = UUID.randomUUID();
        this.subastaId = subastaId;
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.monto = monto;
        this.creadaEn = creadaEn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubastaId() {
        return subastaId;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getUsuarioNombre() {
        return usuarioNombre;
    }

    public long getMonto() {
        return monto;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }
}
