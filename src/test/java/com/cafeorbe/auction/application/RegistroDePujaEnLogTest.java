package com.cafeorbe.auction.application;

import com.cafeorbe.auction.LogsJson;
import com.cafeorbe.auction.api.UsuarioActual;
import com.cafeorbe.auction.domain.MotivoRechazo;
import com.cafeorbe.auction.domain.ResultadoPuja;
import com.cafeorbe.contracts.Rol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-01: cada puja deja una línea en el log JSON con su resultado y el correlationId de la petición. Con contexto de
 * Spring porque el codificador JSON de Spring Boot (LogsJson) lo necesita.
 */
@SpringBootTest
@ActiveProfiles("test")
class RegistroDePujaEnLogTest {

    private final UUID subasta = UUID.randomUUID();
    private final UsuarioActual compradora = new UsuarioActual(UUID.randomUUID(), "Ana", Rol.COMPRADOR);

    @AfterEach
    void limpiar() {
        MDC.remove("correlationId");
    }

    @Test
    @DisplayName("OBS-01 · Una puja rechazada, incluso por el rechazo temprano sin evento, queda en el log con su motivo")
    void rechazadaQuedaEnElLog() throws Exception {
        var registro = new RegistrarPujaService.Registro(
                new ResultadoPuja.Rechazada(MotivoRechazo.INCREMENTO_MINIMO, 900), 1000, 3, false);
        try (var logs = LogsJson.de(RegistrarPujaService.class.getName())) {
            MDC.put("correlationId", "corr-de-la-puja");
            RegistrarPujaService.registrarEnLog(subasta, compradora, 900, registro);

            var linea = logs.mensajeUnaVez("Puja registrada: rechazada", "corr-de-la-puja");
            assertThat(linea.path("resultado").asText()).isEqualTo("rechazada");
            assertThat(linea.path("motivo").asText()).isEqualTo("INCREMENTO_MINIMO");
            assertThat(linea.path("subastaId").asText()).isEqualTo(subasta.toString());
            assertThat(linea.path("monto").asLong()).isEqualTo(900);
        }
    }

    @Test
    @DisplayName("OBS-01 · Una puja aceptada queda en el log con el correlationId de la petición")
    void aceptadaQuedaEnElLog() throws Exception {
        var registro = new RegistrarPujaService.Registro(new ResultadoPuja.Aceptada(null, null), 1100, 4);
        try (var logs = LogsJson.de(RegistrarPujaService.class.getName())) {
            MDC.put("correlationId", "corr-de-la-puja");
            RegistrarPujaService.registrarEnLog(subasta, compradora, 1000, registro);

            var linea = logs.mensajeUnaVez("Puja registrada: aceptada", "corr-de-la-puja");
            assertThat(linea.path("resultado").asText()).isEqualTo("aceptada");
            assertThat(linea.path("usuario").asText()).isEqualTo(compradora.id().toString());
        }
    }
}
