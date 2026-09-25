package com.cafeorbe.auction.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Agregado raíz. Concentra las reglas de negocio: creación, ficha del lote, reglas de puja,
 * inicio y validación de pujas. Los controladores y casos de uso no las duplican.
 */
@Entity
@Table(name = "subasta")
public class Subasta {

    @Id
    private UUID id;

    @Version
    private long version;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 1000)
    private String descripcion;

    @Column(name = "fecha_inicio", nullable = false)
    private Instant fechaInicio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoSubasta estado;

    @Column(name = "subastador_id", nullable = false, updatable = false)
    private UUID subastadorId;

    @Column(name = "subastador_nombre", nullable = false, length = 50)
    private String subastadorNombre;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "identificacion", column = @Column(name = "ficha_identificacion", length = 100)),
            @AttributeOverride(name = "tipoCafe", column = @Column(name = "ficha_tipo_cafe", length = 100)),
            @AttributeOverride(name = "pesoKg", column = @Column(name = "ficha_peso_kg", precision = 10, scale = 2)),
            @AttributeOverride(name = "edadMeses", column = @Column(name = "ficha_edad_meses")),
            @AttributeOverride(name = "observaciones", column = @Column(name = "ficha_observaciones", length = 1000))
    })
    private FichaLote ficha;

    @Column(name = "duracion_minutos")
    private Integer duracionMinutos;

    @Column(name = "precio_base")
    private Long precioBase;

    @Column(name = "incremento_minimo")
    private Long incrementoMinimo;

    @Column(name = "reglas_version", nullable = false)
    private int reglasVersion;

    @Column(name = "hora_inicio")
    private Instant horaInicio;

    @Column(name = "hora_fin")
    private Instant horaFin;

    @Column(name = "precio_actual")
    private Long precioActual;

    @Column(name = "lider_id")
    private UUID liderId;

    @Column(name = "lider_nombre", length = 50)
    private String liderNombre;

    @Column(name = "cantidad_pujas", nullable = false)
    private int cantidadPujas;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    protected Subasta() {
    }

    /** HU-08: crea la subasta en estado Programada. */
    public static Subasta programar(String nombre, String descripcion, Instant fechaInicio,
                                    UUID subastadorId, String subastadorNombre, Instant ahora) {
        if (nombre == null || nombre.isBlank()) {
            throw ReglaDeNegocioException.validacion("nombre", "El nombre es obligatorio");
        }
        if (nombre.trim().length() > 120) {
            throw ReglaDeNegocioException.validacion("nombre", "El nombre no puede superar 120 caracteres");
        }
        if (fechaInicio == null) {
            throw ReglaDeNegocioException.validacion("fechaInicio", "La fecha de inicio es obligatoria");
        }
        if (fechaInicio.isBefore(ahora.truncatedTo(ChronoUnit.MINUTES))) {
            throw ReglaDeNegocioException.validacion("fechaInicio", "La fecha de inicio debe ser futura");
        }
        Subasta s = new Subasta();
        s.id = UUID.randomUUID();
        s.nombre = nombre.trim();
        s.descripcion = descripcion == null || descripcion.isBlank() ? null : descripcion.trim();
        s.fechaInicio = fechaInicio;
        s.estado = EstadoSubasta.PROGRAMADA;
        s.subastadorId = subastadorId;
        s.subastadorNombre = subastadorNombre;
        s.creadaEn = ahora;
        return s;
    }

    /** HU-09: registra o reemplaza la ficha técnica. Solo mientras la subasta está Programada. */
    public void registrarFicha(FichaLote nueva) {
        if (estado != EstadoSubasta.PROGRAMADA) {
            throw ReglaDeNegocioException.conflicto("La ficha no se puede editar con la subasta iniciada");
        }
        this.ficha = nueva;
    }

    /** HU-10: configura duración, precio base e incremento. Cada cambio sube la versión de las reglas. */
    public void configurarReglas(ReglasDePuja reglas) {
        if (estado != EstadoSubasta.PROGRAMADA) {
            throw ReglaDeNegocioException.conflicto("Las reglas no se pueden cambiar con la subasta iniciada");
        }
        this.duracionMinutos = reglas.duracionMinutos();
        this.precioBase = reglas.precioBase();
        this.incrementoMinimo = reglas.incrementoMinimo();
        this.reglasVersion++;
    }

    /** HU-12: pasa a En curso, fija la hora de fin con la duración configurada y habilita las pujas. */
    public void iniciar(Instant ahora) {
        if (estado != EstadoSubasta.PROGRAMADA) {
            throw ReglaDeNegocioException.conflicto("La subasta ya fue iniciada");
        }
        if (reglas().isEmpty()) {
            throw ReglaDeNegocioException.conflicto("Configura primero el tiempo y las reglas de puja");
        }
        this.estado = EstadoSubasta.EN_CURSO;
        this.horaInicio = ahora;
        this.horaFin = ahora.plus(Duration.ofMinutes(duracionMinutos));
        this.precioActual = precioBase;
    }

    /**
     * HU-13 y HU-14: valida una puja y, si pasa, actualiza precio, líder y contador.
     * No hace entrada/salida: el saldo lo consulta antes el caso de uso.
     * Los Orbes no se reservan al pujar; el cobro ocurre al cierre (Sprint 2).
     *
     * @param saldo saldo del comprador, o vacío si wallet no respondió
     */
    public ResultadoPuja pujar(UUID usuarioId, String usuarioNombre, long monto, OptionalLong saldo, Instant ahora) {
        if (estado.terminada()) {
            return rechazar(MotivoRechazo.SUBASTA_FINALIZADA, monto);
        }
        if (estado != EstadoSubasta.EN_CURSO) {
            return rechazar(MotivoRechazo.SUBASTA_NO_EN_CURSO, monto);
        }
        if (!ahora.isBefore(horaFin)) {
            return rechazar(MotivoRechazo.SUBASTA_FINALIZADA, monto);
        }
        if (usuarioId.equals(liderId)) {
            return rechazar(MotivoRechazo.YA_ERES_LIDER, monto);
        }
        if (monto < siguienteMinimo()) {
            return rechazar(MotivoRechazo.INCREMENTO_MINIMO, monto);
        }
        if (saldo.isEmpty()) {
            return rechazar(MotivoRechazo.SALDO_NO_DISPONIBLE, monto);
        }
        if (saldo.getAsLong() < monto) {
            return rechazar(MotivoRechazo.SALDO_INSUFICIENTE, monto);
        }
        this.precioActual = monto;
        this.liderId = usuarioId;
        this.liderNombre = usuarioNombre;
        this.cantidadPujas++;
        return new ResultadoPuja.Aceptada(new Puja(id, usuarioId, usuarioNombre, monto, ahora));
    }

    private ResultadoPuja rechazar(MotivoRechazo motivo, long monto) {
        return new ResultadoPuja.Rechazada(motivo, monto);
    }

    /** Monto mínimo de la próxima puja: precio actual + incremento. Es lo que propone el botón de puja rápida. */
    public long siguienteMinimo() {
        return precioActualOBase() + incrementoMinimo;
    }

    /** Precio vigente; mientras no hay pujas es el precio base. */
    public long precioActualOBase() {
        if (precioActual != null) {
            return precioActual;
        }
        if (precioBase == null) {
            throw ReglaDeNegocioException.conflicto("La subasta no tiene reglas de puja configuradas");
        }
        return precioBase;
    }

    public Optional<ReglasDePuja> reglas() {
        if (duracionMinutos == null || precioBase == null || incrementoMinimo == null) {
            return Optional.empty();
        }
        return Optional.of(new ReglasDePuja(duracionMinutos, precioBase, incrementoMinimo));
    }

    public boolean esDe(UUID usuarioId) {
        return subastadorId.equals(usuarioId);
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public Instant getFechaInicio() {
        return fechaInicio;
    }

    public EstadoSubasta getEstado() {
        return estado;
    }

    public UUID getSubastadorId() {
        return subastadorId;
    }

    public String getSubastadorNombre() {
        return subastadorNombre;
    }

    public Optional<FichaLote> getFicha() {
        return Optional.ofNullable(ficha);
    }

    public Integer getDuracionMinutos() {
        return duracionMinutos;
    }

    public Long getPrecioBase() {
        return precioBase;
    }

    public Long getIncrementoMinimo() {
        return incrementoMinimo;
    }

    public int getReglasVersion() {
        return reglasVersion;
    }

    public Instant getHoraInicio() {
        return horaInicio;
    }

    public Instant getHoraFin() {
        return horaFin;
    }

    public UUID getLiderId() {
        return liderId;
    }

    public String getLiderNombre() {
        return liderNombre;
    }

    public int getCantidadPujas() {
        return cantidadPujas;
    }
}
