package com.cafeorbe.auction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La ficha se valida en el constructor del record, antes de llegar a la base. Los límites existen porque
 * superarlos daba HTTP 500 en vez de un 400 con el campo señalado (hallazgos 1 y 3), así que se comprueba
 * que cada excepción de validación dice qué campo hay que corregir.
 */
class FichaLoteTest {

    private static final BigDecimal PESO = new BigDecimal("12.5");

    private FichaLote ficha(String identificacion, String tipoCafe, BigDecimal peso, Integer edadMeses) {
        return new FichaLote(identificacion, tipoCafe, peso, edadMeses, null);
    }

    private void validar(String campo, String mensaje, String id, String tipo, BigDecimal peso, Integer edad) {
        assertThatThrownBy(() -> ficha(id, tipo, peso, edad))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage(mensaje)
                .extracting(e -> ((ReglaDeNegocioException) e).getCampo())
                .isEqualTo(Optional.of(campo));
    }

    @Test
    @DisplayName("Una ficha completa es válida y conserva sus datos")
    void fichaCompleta() {
        var ficha = ficha("L-001", "Arábica", PESO, 12);

        assertThat(ficha.identificacion()).isEqualTo("L-001");
        assertThat(ficha.tipoCafe()).isEqualTo("Arábica");
        assertThat(ficha.pesoKg()).isEqualByComparingTo(PESO);
        assertThat(ficha.edadMeses()).isEqualTo(12);
    }

    @Test
    @DisplayName("Los textos se recortan y las observaciones vacías quedan en null, no en cadena vacía")
    void normalizaLosTextos() {
        var ficha = new FichaLote("  L-001  ", "  Arábica  ", PESO, 12, "   ");

        assertThat(ficha.identificacion()).isEqualTo("L-001");
        assertThat(ficha.tipoCafe()).isEqualTo("Arábica");
        assertThat(ficha.observaciones()).isNull();
    }

    @Test
    @DisplayName("La identificación es obligatoria: se rechaza nula, vacía o solo espacios")
    void identificacionObligatoria() {
        validar("identificacion", "La identificación es obligatoria", null, "Arábica", PESO, 12);
        validar("identificacion", "La identificación es obligatoria", "", "Arábica", PESO, 12);
        validar("identificacion", "La identificación es obligatoria", "   ", "Arábica", PESO, 12);
    }

    @Test
    @DisplayName("El tipo de café es obligatorio")
    void tipoDeCafeObligatorio() {
        validar("tipoCafe", "El tipo de café es obligatorio", "L-001", null, PESO, 12);
        validar("tipoCafe", "El tipo de café es obligatorio", "L-001", "  ", PESO, 12);
    }

    @Test
    @DisplayName("El peso debe ser mayor que cero: ni nulo, ni cero, ni negativo")
    void pesoMayorQueCero() {
        validar("pesoKg", "El peso debe ser mayor que cero", "L-001", "Arábica", null, 12);
        validar("pesoKg", "El peso debe ser mayor que cero", "L-001", "Arábica", BigDecimal.ZERO, 12);
        validar("pesoKg", "El peso debe ser mayor que cero", "L-001", "Arábica", new BigDecimal("-1"), 12);
    }

    @Test
    @DisplayName("La edad son meses desde cero: cero es válido (café recién cosechado), negativo no")
    void edadDesdeCero() {
        validar("edadMeses", "La edad debe ser un número de meses (0 o más)", "L-001", "Arábica", PESO, null);
        validar("edadMeses", "La edad debe ser un número de meses (0 o más)", "L-001", "Arábica", PESO, -1);

        assertThatCode(() -> ficha("L-001", "Arábica", PESO, 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Los textos cortos no pueden superar 100 caracteres, en ninguno de los dos campos")
    void limiteDeTextosCortos() {
        var largo = "x".repeat(FichaLote.MAX_TEXTO_CORTO + 1);

        validar("identificacion", "La identificación no puede superar 100 caracteres", largo, "Arábica", PESO, 12);
        validar("tipoCafe", "El tipo de café no puede superar 100 caracteres", "L-001", largo, PESO, 12);
    }

    @Test
    @DisplayName("El límite es exacto: 100 caracteres sí valen, 101 no")
    void elLimiteEsExacto() {
        var justo = "x".repeat(FichaLote.MAX_TEXTO_CORTO);

        assertThatCode(() -> ficha(justo, justo, PESO, 12)).doesNotThrowAnyException();
        assertThatCode(() -> ficha(justo + "x", justo, PESO, 12))
                .isInstanceOf(ReglaDeNegocioException.class);
    }

    @Test
    @DisplayName("Las observaciones admiten 1000 caracteres, uno más ya no")
    void limiteDeObservaciones() {
        var justo = "x".repeat(FichaLote.MAX_OBSERVACIONES);

        assertThatCode(() -> new FichaLote("L-001", "Arábica", PESO, 12, justo)).doesNotThrowAnyException();
        assertThatCode(() -> new FichaLote("L-001", "Arábica", PESO, 12, justo + "x"))
                .isInstanceOf(ReglaDeNegocioException.class);
    }
}