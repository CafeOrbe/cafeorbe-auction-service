package com.cafeorbe.auction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pruebas unitarias de las reglas del agregado, sin Spring ni base de datos. */
class SubastaTest {

    static final Instant AHORA = Instant.parse("2026-09-21T15:00:00Z");
    static final UUID LUIS = UUID.randomUUID();
    static final UUID ANA = UUID.randomUUID();
    static final UUID BRUNO = UUID.randomUUID();

    private Subasta programada() {
        return Subasta.programar("Lote Orbe 1", "Descripción", AHORA.plus(Duration.ofDays(1)), LUIS, "Luis", AHORA);
    }

    private Subasta enCurso() {
        Subasta s = programada();
        s.configurarReglas(new ReglasDePuja(10, 100, 10));
        s.iniciar(AHORA);
        return s;
    }

    // ── HU-08 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HU-08 · Creación exitosa: queda Programada")
    void creacionExitosa() {
        Subasta s = programada();
        assertThat(s.getEstado()).isEqualTo(EstadoSubasta.PROGRAMADA);
        assertThat(s.esDe(LUIS)).isTrue();
    }

    @Test
    @DisplayName("HU-08 · Campos obligatorios: nombre vacío o sin fecha de inicio")
    void camposObligatorios() {
        assertThatThrownBy(() -> Subasta.programar("  ", null, AHORA.plusSeconds(60), LUIS, "Luis", AHORA))
                .hasMessage("El nombre es obligatorio");
        assertThatThrownBy(() -> Subasta.programar("Lote", null, null, LUIS, "Luis", AHORA))
                .hasMessage("La fecha de inicio es obligatoria");
    }

    @Test
    @DisplayName("HU-08 · Fecha en el pasado: La fecha de inicio debe ser futura")
    void fechaEnElPasado() {
        assertThatThrownBy(() -> Subasta.programar("Lote", null, AHORA.minusSeconds(1), LUIS, "Luis", AHORA))
                .hasMessage("La fecha de inicio debe ser futura");
    }

    // ── HU-09 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HU-09 · Registro de la ficha asociada a la subasta")
    void registroDeFicha() {
        Subasta s = programada();
        s.registrarFicha(new FichaLote("L-001", "Arábica", new BigDecimal("450.5"), 36, "Sana"));
        assertThat(s.getFicha()).isPresent();
        assertThat(s.getFicha().get().tipoCafe()).isEqualTo("Arábica");
    }

    @Test
    @DisplayName("HU-09 · Edición bloqueada tras iniciar")
    void edicionBloqueadaTrasIniciar() {
        Subasta s = enCurso();
        assertThatThrownBy(() -> s.registrarFicha(new FichaLote("L-001", "Arábica", BigDecimal.TEN, 12, null)))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("La ficha no se puede editar con la subasta iniciada");
    }

    @Test
    @DisplayName("HU-09 · Textos más largos que la columna se rechazan con el campo señalado (hallazgos 1 y 3)")
    void fichaConTextosLargos() {
        assertThatThrownBy(() -> new FichaLote("X".repeat(101), "Arábica", BigDecimal.TEN, 1, null))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class,
                        e -> assertThat(e.getCampo()).contains("identificacion"));
        assertThatThrownBy(() -> new FichaLote("L-1", "Arábica", BigDecimal.TEN, 1, "o".repeat(1001)))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class,
                        e -> assertThat(e.getCampo()).contains("observaciones"));
        assertThat(new FichaLote("X".repeat(100), "Arábica", BigDecimal.TEN, 1, "o".repeat(1000)).identificacion())
                .hasSize(100);
    }

    @Test
    @DisplayName("HU-09 · Peso y edad inválidos señalan su campo (hallazgo 3)")
    void fichaConValoresInvalidos() {
        assertThatThrownBy(() -> new FichaLote("L-1", "Arábica", BigDecimal.ZERO, 1, null))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class, e -> assertThat(e.getCampo()).contains("pesoKg"));
        assertThatThrownBy(() -> new FichaLote("L-1", "Arábica", BigDecimal.TEN, -1, null))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class, e -> assertThat(e.getCampo()).contains("edadMeses"));
    }

    // ── HU-10 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HU-10 · Configuración válida: se asocia a la subasta y sube la versión de las reglas")
    void configuracionValida() {
        Subasta s = programada();
        s.configurarReglas(new ReglasDePuja(10, 100, 10));
        s.configurarReglas(new ReglasDePuja(15, 200, 20));
        assertThat(s.reglas()).contains(new ReglasDePuja(15, 200, 20));
        assertThat(s.getReglasVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("HU-10 · Valores inválidos: precio base 0 o incremento 0")
    void valoresInvalidos() {
        assertThatThrownBy(() -> new ReglasDePuja(10, 0, 10)).hasMessage("Los valores deben ser mayores que cero");
        assertThatThrownBy(() -> new ReglasDePuja(10, 100, 0)).hasMessage("Los valores deben ser mayores que cero");
        assertThatThrownBy(() -> new ReglasDePuja(0, 100, 10)).hasMessage("Los valores deben ser mayores que cero");
    }

    // ── HU-12 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HU-12 · Inicio: pasa a En curso, arranca el temporizador y el precio actual es el base")
    void inicioDeLaSubasta() {
        Subasta s = enCurso();
        assertThat(s.getEstado()).isEqualTo(EstadoSubasta.EN_CURSO);
        assertThat(s.getHoraInicio()).isEqualTo(AHORA);
        assertThat(s.getHoraFin()).isEqualTo(AHORA.plus(Duration.ofMinutes(10)));
        assertThat(s.precioActualOBase()).isEqualTo(100);
    }

    @Test
    @DisplayName("HU-12 · Inicio sin configuración: Configura primero el tiempo y las reglas de puja")
    void inicioSinConfiguracion() {
        assertThatThrownBy(() -> programada().iniciar(AHORA))
                .hasMessage("Configura primero el tiempo y las reglas de puja");
    }

    // ── HU-13 y HU-14 ─────────────────────────────────────────────────────

    private static ResultadoPuja.Rechazada rechazada(ResultadoPuja r) {
        assertThat(r).isInstanceOf(ResultadoPuja.Rechazada.class);
        return (ResultadoPuja.Rechazada) r;
    }

    @Test
    @DisplayName("HU-13 · Puja rápida exitosa: precio actual 100 + incremento 10 → 110 y queda como líder")
    void pujaRapidaExitosa() {
        Subasta s = enCurso();
        assertThat(s.siguienteMinimo()).isEqualTo(110);

        ResultadoPuja r = s.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA.plusSeconds(5));

        assertThat(r).isInstanceOf(ResultadoPuja.Aceptada.class);
        assertThat(s.precioActualOBase()).isEqualTo(110);
        assertThat(s.getLiderId()).isEqualTo(ANA);
        assertThat(s.getLiderNombre()).isEqualTo("Ana");
        assertThat(s.getCantidadPujas()).isEqualTo(1);
        assertThat(s.siguienteMinimo()).isEqualTo(120);
    }

    @Test
    @DisplayName("HU-13 · Siendo líder no se puede volver a pujar: Vas ganando")
    void liderNoPuedePujarDeNuevo() {
        Subasta s = enCurso();
        s.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA.plusSeconds(5));

        var r = rechazada(s.pujar(ANA, "Ana", 120, OptionalLong.of(500), AHORA.plusSeconds(6)));

        assertThat(r.motivo()).isEqualTo(MotivoRechazo.YA_ERES_LIDER);
        assertThat(r.motivo().mensaje()).isEqualTo("Vas ganando");
        assertThat(s.getCantidadPujas()).isEqualTo(1);
    }

    @Test
    @DisplayName("HU-14 · Puja inferior al incremento mínimo: No cumple el incremento mínimo")
    void pujaBajoElIncremento() {
        Subasta s = enCurso();
        var r = rechazada(s.pujar(ANA, "Ana", 105, OptionalLong.of(500), AHORA.plusSeconds(5)));
        assertThat(r.motivo()).isEqualTo(MotivoRechazo.INCREMENTO_MINIMO);
        assertThat(r.motivo().mensaje()).isEqualTo("No cumple el incremento mínimo");
        assertThat(s.getLiderId()).isNull();
    }

    @Test
    @DisplayName("HU-14 · Puja sin saldo: Orbes insuficientes y no cambia el líder")
    void pujaSinSaldo() {
        Subasta s = enCurso();
        s.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA.plusSeconds(5));

        var r = rechazada(s.pujar(BRUNO, "Bruno", 120, OptionalLong.of(50), AHORA.plusSeconds(6)));

        assertThat(r.motivo()).isEqualTo(MotivoRechazo.SALDO_INSUFICIENTE);
        assertThat(r.motivo().mensaje()).isEqualTo("Orbes insuficientes");
        assertThat(s.getLiderId()).isEqualTo(ANA);
        assertThat(s.precioActualOBase()).isEqualTo(110);
    }

    @Test
    @DisplayName("HU-14 · Si wallet no responde la puja se rechaza sin cambiar nada")
    void walletNoDisponible() {
        Subasta s = enCurso();
        var r = rechazada(s.pujar(ANA, "Ana", 110, OptionalLong.empty(), AHORA.plusSeconds(5)));
        assertThat(r.motivo()).isEqualTo(MotivoRechazo.SALDO_NO_DISPONIBLE);
        assertThat(s.getLiderId()).isNull();
    }

    @Test
    @DisplayName("HU-14 · Puja válida registrada con usuario, monto y hora")
    void pujaValidaRegistrada() {
        Subasta s = enCurso();
        var aceptada = (ResultadoPuja.Aceptada) s.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA.plusSeconds(5));
        assertThat(aceptada.puja().getUsuarioId()).isEqualTo(ANA);
        assertThat(aceptada.puja().getMonto()).isEqualTo(110);
        assertThat(aceptada.puja().getCreadaEn()).isEqualTo(AHORA.plusSeconds(5));
    }

    @Test
    @DisplayName("Una puja sobre una subasta Programada o terminada se rechaza con motivo tipificado")
    void pujaFueraDeEstado() {
        Subasta programada = programada();
        programada.configurarReglas(new ReglasDePuja(10, 100, 10));
        assertThat(rechazada(programada.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA)).motivo())
                .isEqualTo(MotivoRechazo.SUBASTA_NO_EN_CURSO);
    }

    @Test
    @DisplayName("Una puja llegada después de la hora de fin se rechaza: La subasta ya finalizó")
    void pujaTardia() {
        Subasta s = enCurso();
        var r = rechazada(s.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA.plus(Duration.ofMinutes(10))));
        assertThat(r.motivo()).isEqualTo(MotivoRechazo.SUBASTA_FINALIZADA);
        assertThat(r.motivo().mensaje()).isEqualTo("La subasta ya finalizó");
    }

    // ── HU-17 · Tiempo restante según el servidor ──────────────────────────

    @Test
    @DisplayName("HU-17 · El servidor entrega el tiempo restante; es 0 al vencer o si la subasta no está en curso")
    void tiempoRestante() {
        Subasta s = enCurso();
        assertThat(s.segundosRestantes(AHORA)).isEqualTo(600);
        assertThat(s.segundosRestantes(AHORA.plusSeconds(545))).isEqualTo(55);
        assertThat(s.segundosRestantes(AHORA.plusSeconds(700))).isZero();
        assertThat(programada().segundosRestantes(AHORA)).isZero();
    }

    // ── HU-18 · Anti-sniping ───────────────────────────────────────────────

    private Subasta enCursoCon(AntiSniping regla) {
        Subasta s = programada();
        s.configurarReglas(new ReglasDePuja(10, 100, 10));
        s.iniciar(AHORA, regla);
        return s;
    }

    @Test
    @DisplayName("HU-18 · Extensión por puja al final: quedan 15 s, ventana de 30 s → se extiende 30 s")
    void extensionPorPujaAlFinal() {
        Subasta s = enCursoCon(new AntiSniping(30, 3));
        Instant finOriginal = s.getHoraFin();

        var aceptada = (ResultadoPuja.Aceptada) s.pujar(ANA, "Ana", 110, OptionalLong.of(500), finOriginal.minusSeconds(15));

        assertThat(aceptada.extension()).isNotNull();
        assertThat(aceptada.extension().segundos()).isEqualTo(30);
        assertThat(aceptada.extension().numero()).isEqualTo(1);
        assertThat(aceptada.extension().horaFin()).isEqualTo(finOriginal.plusSeconds(30));
        assertThat(s.getHoraFin()).isEqualTo(finOriginal.plusSeconds(30));
        assertThat(s.getExtensiones()).isEqualTo(1);
    }

    @Test
    @DisplayName("HU-18 · Una puja fuera de la ventana final no extiende el tiempo")
    void pujaFueraDeLaVentana() {
        Subasta s = enCursoCon(new AntiSniping(30, 3));
        Instant finOriginal = s.getHoraFin();

        var aceptada = (ResultadoPuja.Aceptada) s.pujar(ANA, "Ana", 110, OptionalLong.of(500), finOriginal.minusSeconds(31));

        assertThat(aceptada.extension()).isNull();
        assertThat(s.getHoraFin()).isEqualTo(finOriginal);
    }

    @Test
    @DisplayName("HU-18 · Límite de extensiones: alcanzado el máximo, otra puja en la ventana final no extiende")
    void limiteDeExtensiones() {
        Subasta s = enCursoCon(new AntiSniping(30, 1));
        Instant finOriginal = s.getHoraFin();
        s.pujar(ANA, "Ana", 110, OptionalLong.of(500), finOriginal.minusSeconds(15));
        Instant finExtendido = s.getHoraFin();

        var segunda = (ResultadoPuja.Aceptada) s.pujar(BRUNO, "Bruno", 120, OptionalLong.of(500), finExtendido.minusSeconds(10));

        assertThat(segunda.extension()).isNull();
        assertThat(s.getHoraFin()).isEqualTo(finExtendido);
        assertThat(s.getExtensiones()).isEqualTo(1);
        // La subasta cierra con normalidad en la hora ya extendida.
        s.cerrar(finExtendido);
        assertThat(s.getEstado()).isEqualTo(EstadoSubasta.FINALIZADA);
    }

    // ── HU-19 · Cierre automático ──────────────────────────────────────────

    @Test
    @DisplayName("HU-19 · Cierre con ganador: Finalizada, la última puja válida gana y se bloquean nuevas pujas")
    void cierreConGanador() {
        Subasta s = enCurso();
        s.pujar(ANA, "Ana", 110, OptionalLong.of(500), AHORA.plusSeconds(5));
        s.pujar(BRUNO, "Bruno", 120, OptionalLong.of(500), AHORA.plusSeconds(6));

        s.cerrar(s.getHoraFin());

        assertThat(s.getEstado()).isEqualTo(EstadoSubasta.FINALIZADA);
        assertThat(s.getLiderId()).isEqualTo(BRUNO);
        assertThat(s.precioActualOBase()).isEqualTo(120);
        assertThat(s.getCerradaEn()).isEqualTo(s.getHoraFin());
        assertThat(rechazada(s.pujar(ANA, "Ana", 130, OptionalLong.of(500), s.getHoraFin().plusSeconds(1))).motivo())
                .isEqualTo(MotivoRechazo.SUBASTA_FINALIZADA);
    }

    @Test
    @DisplayName("HU-19 · Cierre sin pujas: la subasta queda Desierta")
    void cierreSinPujas() {
        Subasta s = enCurso();
        s.cerrar(s.getHoraFin().plusSeconds(1));
        assertThat(s.getEstado()).isEqualTo(EstadoSubasta.DESIERTA);
        assertThat(s.getLiderId()).isNull();
    }

    @Test
    @DisplayName("HU-19 · No se puede cerrar antes de tiempo ni una subasta que no está en curso")
    void cierreFueraDeLugar() {
        Subasta s = enCurso();
        assertThatThrownBy(() -> s.cerrar(s.getHoraFin().minusSeconds(1))).hasMessage("La subasta aún no ha terminado");
        assertThatThrownBy(() -> programada().cerrar(AHORA)).hasMessage("La subasta no está en curso");
    }

    // ── Límites y bloqueos que no dependen del repositorio ─────────────────

    @Test
    @DisplayName("Un nombre de solo espacios también se rechaza: no es un nombre")
    void nombreDeSoloEspacios() {
        assertThatThrownBy(() -> Subasta.programar("   ", null, AHORA.plusSeconds(60), LUIS, "Luis", AHORA))
                .hasMessage("El nombre es obligatorio");
    }

    @Test
    @DisplayName("Un nombre de más de 120 caracteres se rechaza antes de llegar a la base (hallazgo 1)")
    void nombreDemasiadoLargo() {
        var largo = "L".repeat(121);

        assertThatThrownBy(() -> Subasta.programar(largo, null, AHORA.plusSeconds(60), LUIS, "Luis", AHORA))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El nombre no puede superar 120 caracteres");
    }

    @Test
    @DisplayName("Una descripción de solo espacios queda en null, no como cadena vacía")
    void descripcionVaciaQuedaEnNull() {
        assertThat(programada().getDescripcion()).isEqualTo("Descripción");

        var sinDescripcion = Subasta.programar("Lote", "   ", AHORA.plusSeconds(60), LUIS, "Luis", AHORA);
        assertThat(sinDescripcion.getDescripcion()).isNull();
    }

    @Test
    @DisplayName("Con la subasta ya iniciada, cambiar las reglas es un conflicto, no un permiso")
    void cambiarReglasTrasIniciar() {
        Subasta s = enCurso();

        assertThatThrownBy(() -> s.configurarReglas(new ReglasDePuja(20, 200, 20)))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("Las reglas no se pueden cambiar con la subasta iniciada");
    }

    @Test
    @DisplayName("Iniciar dos veces es un conflicto: la hora de inicio no se pisa")
    void iniciarDosVeces() {
        Subasta s = enCurso();
        var horaOriginal = s.getHoraInicio();

        assertThatThrownBy(() -> s.iniciar(AHORA.plus(Duration.ofHours(1))))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("La subasta ya fue iniciada");
        assertThat(s.getHoraInicio()).isEqualTo(horaOriginal);
    }

    @Test
    @DisplayName("Sin reglas configuradas no hay precio: pedirlo es un conflicto explícito")
    void precioActualSinReglas() {
        Subasta s = programada();

        assertThatThrownBy(s::precioActualOBase)
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("La subasta no tiene reglas de puja configuradas");
        assertThatThrownBy(s::siguienteMinimo)
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("La subasta no tiene reglas de puja configuradas");
    }
}
