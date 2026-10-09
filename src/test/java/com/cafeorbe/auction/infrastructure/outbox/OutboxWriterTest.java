package com.cafeorbe.auction.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWriterTest {

    private static final Instant AHORA = Instant.parse("2026-10-08T12:00:00Z");
    private final Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);

    @Test
    @DisplayName("registrar(): guarda el evento serializado con su tipo y la hora del reloj")
    void guardaElEvento() {
        OutboxRepository repositorio = mock(OutboxRepository.class);

        new OutboxWriter(repositorio, new ObjectMapper().findAndRegisterModules(), reloj)
                .registrar("puja.aceptada", Map.of("monto", 300));

        ArgumentCaptor<OutboxEvento> guardado = ArgumentCaptor.forClass(OutboxEvento.class);
        verify(repositorio).save(guardado.capture());
        OutboxEvento evento = guardado.getValue();
        assertThat(evento.getTipo()).isEqualTo("puja.aceptada");
        assertThat(evento.getEventId()).isNotNull();
        assertThat(evento.getId()).isNull();
        assertThat(evento.getPublicadoEn()).isNull();
        assertThat(evento.getPayload()).contains("\"monto\":300").contains("puja.aceptada");
    }

    @Test
    @DisplayName("registrar(): si el evento no se puede serializar falla y no guarda nada")
    void fallaSiNoSePuedeSerializar() throws JsonProcessingException {
        OutboxRepository repositorio = mock(OutboxRepository.class);
        ObjectMapper json = mock(ObjectMapper.class);
        when(json.writeValueAsString(any())).thenThrow(new JsonProcessingException("roto") { });

        OutboxWriter escritor = new OutboxWriter(repositorio, json, reloj);

        assertThatThrownBy(() -> escritor.registrar("puja.aceptada", "datos"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("puja.aceptada");
        verify(repositorio, never()).save(any());
    }
}
