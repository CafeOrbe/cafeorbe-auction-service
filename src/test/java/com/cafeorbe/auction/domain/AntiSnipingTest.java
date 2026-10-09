package com.cafeorbe.auction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AntiSnipingTest {

    @Test
    @DisplayName("Una ventana o un máximo negativos se rechazan")
    void rechazaNegativos() {
        assertThatThrownBy(() -> new AntiSniping(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AntiSniping(30, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DESACTIVADO no extiende el tiempo nunca")
    void desactivado() {
        assertThat(AntiSniping.DESACTIVADO).isEqualTo(new AntiSniping(0, 0));
    }
}
