package com.cafeorbe.auction;

import com.cafeorbe.auction.infrastructure.PujaRepository;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import com.cafeorbe.auction.infrastructure.WalletClient;
import com.cafeorbe.auction.infrastructure.outbox.OutboxPublisher;
import com.cafeorbe.auction.infrastructure.outbox.OutboxRepository;
import com.cafeorbe.contracts.Cabeceras;
import com.cafeorbe.contracts.Eventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Escenarios de calidad del Sprint 2 sobre auction-service: SEG-02, REN-01 y OBS-01. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalidadSprint2Test {

    static final String CREDENCIAL = "credencial-de-prueba";
    static final UUID LUIS = UUID.randomUUID();
    static final UUID MARTA = UUID.randomUUID();
    static final UUID ANA = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SubastaRepository subastas;
    @Autowired PujaRepository pujas;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxPublisher publicador;
    @Autowired MeterRegistry metricas;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean WalletClient wallet;

    @BeforeEach
    void limpiar() {
        pujas.deleteAll();
        outbox.deleteAll();
        subastas.deleteAll();
        when(wallet.saldoDe(ANA)).thenReturn(OptionalLong.of(5_000));
    }

    private static MockHttpServletRequestBuilder como(MockHttpServletRequestBuilder req, UUID id, String rol) {
        return req.header("X-Internal-Token", CREDENCIAL).header(Cabeceras.USUARIO_ID, id.toString())
                .header(Cabeceras.USUARIO_NOMBRE, "Usuario").header(Cabeceras.USUARIO_ROL, rol)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String crear(UUID subastador) throws Exception {
        var res = mvc.perform(como(post("/api/subastas"), subastador, "SUBASTADOR")
                        .content("{\"nombre\":\"Lote\",\"descripcion\":\"d\",\"fechaInicio\":\""
                                + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private String enCurso() throws Exception {
        String id = crear(LUIS);
        mvc.perform(como(put("/api/subastas/" + id + "/reglas"), LUIS, "SUBASTADOR")
                .content("{\"duracionMinutos\":10,\"precioBase\":100,\"incrementoMinimo\":10}")).andExpect(status().isOk());
        mvc.perform(como(post("/api/subastas/" + id + "/iniciar"), LUIS, "SUBASTADOR")).andExpect(status().isOk());
        return id;
    }

    private double prohibidos() {
        return metricas.find("cafeorbe.seguridad.eventos").counters().stream()
                .filter(c -> List.of("acceso_prohibido", "acceso_interno_rechazado").contains(c.getId().getTag("evento")))
                .mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("SEG-02 · Matriz de 12 casos negativos: todos 403, nada cambia y todos quedan registrados")
    void matrizDeAutorizacion() throws Exception {
        String programada = crear(LUIS);
        String enVivo = enCurso();
        String ficha = "{\"identificacion\":\"L-001\",\"tipoCafe\":\"Arábica\",\"pesoKg\":450.5,"
                + "\"edadMeses\":36,\"observaciones\":\"Sana\"}";
        long subastasAntes = subastas.count();
        long outboxAntes = outbox.count();
        double antes = prohibidos();

        List<MockHttpServletRequestBuilder> casos = List.of(
                // rol incorrecto: un Comprador intenta acciones del Subastador
                como(post("/api/subastas"), ANA, "COMPRADOR").content("{\"nombre\":\"x\",\"descripcion\":\"d\","
                        + "\"fechaInicio\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}"),
                como(post("/api/subastas/" + programada + "/iniciar"), ANA, "COMPRADOR"),
                como(put("/api/subastas/" + programada + "/ficha"), ANA, "COMPRADOR").content(ficha),
                como(put("/api/subastas/" + programada + "/reglas"), ANA, "COMPRADOR")
                        .content("{\"duracionMinutos\":10,\"precioBase\":1,\"incrementoMinimo\":1}"),
                como(get("/api/subastas/mias"), ANA, "COMPRADOR"),
                // subasta ajena: otro Subastador intenta editar o iniciar la de Luis
                como(put("/api/subastas/" + programada + "/ficha"), MARTA, "SUBASTADOR").content(ficha),
                como(put("/api/subastas/" + programada + "/reglas"), MARTA, "SUBASTADOR")
                        .content("{\"duracionMinutos\":10,\"precioBase\":1,\"incrementoMinimo\":1}"),
                como(post("/api/subastas/" + programada + "/iniciar"), MARTA, "SUBASTADOR"),
                // un Subastador no puja
                como(post("/api/subastas/" + enVivo + "/pujas"), LUIS, "SUBASTADOR").content("{\"monto\":500}"),
                // sin credencial interna: cabeceras fabricadas por quien llama directo al servicio
                post("/api/subastas").header(Cabeceras.USUARIO_ID, MARTA.toString()).header(Cabeceras.USUARIO_ROL,
                        "SUBASTADOR").header(Cabeceras.USUARIO_NOMBRE, "x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"x\",\"descripcion\":\"d\",\"fechaInicio\":\""
                                + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}"),
                post("/api/subastas/" + enVivo + "/pujas").header("X-Internal-Token", "adivinada")
                        .header(Cabeceras.USUARIO_ID, ANA.toString()).header(Cabeceras.USUARIO_ROL, "COMPRADOR")
                        .header(Cabeceras.USUARIO_NOMBRE, "Ana").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monto\":500}"),
                get("/api/subastas"));

        for (var caso : casos) {
            mvc.perform(caso).andExpect(status().isForbidden());
        }

        assertThat(casos).hasSizeGreaterThanOrEqualTo(10);
        assertThat(subastas.count()).isEqualTo(subastasAntes);
        assertThat(outbox.count()).isEqualTo(outboxAntes);
        assertThat(pujas.count()).isZero();
        assertThat(prohibidos() - antes).isEqualTo(casos.size());
        // Los casos legítimos pasan.
        mvc.perform(como(get("/api/subastas/mias"), LUIS, "SUBASTADOR")).andExpect(status().isOk());
        mvc.perform(como(post("/api/subastas/" + enVivo + "/pujas"), ANA, "COMPRADOR").content("{\"monto\":110}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REN-01 · Una puja que ya no supera el precio se rechaza sin transacción, sin wallet y sin evento")
    void rechazoTemprano() throws Exception {
        String id = enCurso();
        long eventos = outbox.count();

        mvc.perform(como(post("/api/subastas/" + id + "/pujas"), ANA, "COMPRADOR").content("{\"monto\":105}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.motivo").value("INCREMENTO_MINIMO"))
                .andExpect(jsonPath("$.siguienteMinimo").value(110))
                .andExpect(jsonPath("$.porEvento").value(false));

        assertThat(outbox.count()).isEqualTo(eventos);
        verify(wallet, never()).saldoDe(any());
    }

    @Test
    @DisplayName("REN-01 · Exactamente una de varias pujas iguales gana el incremento; las demás se rechazan")
    void unaSolaGanaCadaIncremento() throws Exception {
        String id = enCurso();
        UUID[] compradores = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (UUID c : compradores) {
            when(wallet.saldoDe(c)).thenReturn(OptionalLong.of(5_000));
        }
        int aceptadas = 0;
        for (UUID c : compradores) {
            int estado = mvc.perform(como(post("/api/subastas/" + id + "/pujas"), c, "COMPRADOR")
                    .content("{\"monto\":110}")).andReturn().getResponse().getStatus();
            aceptadas += estado == 200 ? 1 : 0;
        }
        assertThat(aceptadas).isEqualTo(1);
        assertThat(pujas.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("REN-01 · Un rechazo decidido con la subasta bloqueada (saldo) sí se publica como evento")
    void rechazoConEvento() throws Exception {
        String id = enCurso();
        when(wallet.saldoDe(ANA)).thenReturn(OptionalLong.of(10));
        mvc.perform(como(post("/api/subastas/" + id + "/pujas"), ANA, "COMPRADOR").content("{\"monto\":110}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.motivo").value("SALDO_INSUFICIENTE"))
                .andExpect(jsonPath("$.porEvento").value(true));
        assertThat(outbox.findAll()).anyMatch(o -> o.getTipo().equals(Eventos.PUJA_RECHAZADA));
    }

    @Test
    @DisplayName("OBS-01 · El correlationId de la puja queda en la outbox y viaja al broker con la secuencia")
    void correlacionYSecuenciaHastaElBroker() throws Exception {
        String id = enCurso();
        mvc.perform(como(post("/api/subastas/" + id + "/pujas"), ANA, "COMPRADOR")
                        .header("X-Correlation-Id", "puja-ana-0001").content("{\"monto\":110}"))
                .andExpect(status().isOk());
        var aceptada = outbox.findAll().stream().filter(o -> o.getTipo().equals(Eventos.PUJA_ACEPTADA)).findFirst()
                .orElseThrow();
        assertThat(aceptada.getCorrelationId()).isEqualTo("puja-ana-0001");

        publicador.publicarPendientes();
        var mensaje = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(Eventos.EXCHANGE), eq(Eventos.PUJA_ACEPTADA), mensaje.capture());
        var cabeceras = mensaje.getValue().getMessageProperties().getHeaders();
        assertThat(cabeceras).containsEntry("x-correlation-id", "puja-ana-0001")
                .containsEntry("x-secuencia", aceptada.getId());
        // Las secuencias crecen en el orden en que ocurrieron los eventos de la subasta.
        verify(rabbit).send(eq(Eventos.EXCHANGE), eq(Eventos.SUBASTA_INICIADA), any(Message.class));
        verify(rabbit, never()).send(anyString(), eq(Eventos.PUJA_RECHAZADA), any(Message.class));
    }

    @Test
    @DisplayName("OBS-01 · Métricas de negocio: pujas por resultado y eventos pendientes en la outbox")
    void metricasDeNegocio() throws Exception {
        String id = enCurso();
        mvc.perform(como(post("/api/subastas/" + id + "/pujas"), ANA, "COMPRADOR").content("{\"monto\":110}"));
        mvc.perform(como(post("/api/subastas/" + id + "/pujas"), ANA, "COMPRADOR").content("{\"monto\":50}"));
        assertThat(metricas.find("cafeorbe.auction.pujas").tag("resultado", "aceptada").counter()).isNotNull();
        assertThat(metricas.find("cafeorbe.auction.pujas").tag("motivo", "INCREMENTO_MINIMO").counter()).isNotNull();
        assertThat(metricas.find("cafeorbe.outbox.pendientes").gauge().value()).isEqualTo(outbox.countByPublicadoEnIsNull());
        assertThat(metricas.find("cafeorbe.auction.pujas.registro").timer().count()).isGreaterThanOrEqualTo(2);
    }
}
