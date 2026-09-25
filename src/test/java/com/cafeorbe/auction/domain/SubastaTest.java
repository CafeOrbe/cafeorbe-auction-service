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
}
