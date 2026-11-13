package com.cafeorbe.auction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OBS-01 / SEG-02: los eventos de seguridad quedan en el log JSON de Azure con un solo correlationId (ver LogsJson).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogsJsonDeSeguridadTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("OBS-01 · El acceso sin credencial interna queda en el log JSON con un solo correlationId")
    void accesoInternoRechazadoQuedaEnElLog() throws Exception {
        try (var logs = LogsJson.de("cafeorbe.seguridad")) {
            mvc.perform(get("/api/subastas").header("X-Correlation-Id", "corr-logs-json-1"))
                    .andExpect(status().isForbidden());

            logs.registradoUnaVez("acceso_interno_rechazado", "corr-logs-json-1");
        }
    }

    @Autowired com.cafeorbe.auction.infrastructure.outbox.OutboxRepository outbox;
    @Autowired com.cafeorbe.auction.infrastructure.outbox.OutboxPublisher publicador;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;

    @Test
    @DisplayName("OBS-01 · El evento publicado lleva su correlationId aunque el hilo tenga otro en el MDC, y lo restaura")
    void publicadoConMdcDeOtroCierre() throws Exception {
        outbox.deleteAll();
        outbox.save(new com.cafeorbe.auction.infrastructure.outbox.OutboxEvento(java.util.UUID.randomUUID(),
                "puja.aceptada", "{}", java.time.Instant.now(), "corr-de-la-puja"));
        try (var logs = LogsJson.de(com.cafeorbe.auction.infrastructure.outbox.OutboxPublisher.class.getName())) {
            org.slf4j.MDC.put("correlationId", "cierre-otra-subasta");
            try {
                publicador.publicarPendientes();
                org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("cierre-otra-subasta");
            } finally {
                org.slf4j.MDC.remove("correlationId");
            }
            logs.registradoUnaVez("puja.aceptada", "corr-de-la-puja");
        }
    }
}
