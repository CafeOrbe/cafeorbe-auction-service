package com.cafeorbe.auction;

import com.cafeorbe.auction.infrastructure.PujaRepository;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import com.cafeorbe.auction.infrastructure.WalletClient;
import com.cafeorbe.auction.infrastructure.outbox.OutboxPublisher;
import com.cafeorbe.auction.infrastructure.outbox.OutboxRepository;
import com.cafeorbe.contracts.Cabeceras;
import com.cafeorbe.contracts.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubastaApiTest {

    static final UUID LUIS = UUID.randomUUID();
    static final UUID MARTA = UUID.randomUUID();
    static final UUID ANA = UUID.randomUUID();
    static final UUID BRUNO = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SubastaRepository subastas;
    @Autowired PujaRepository pujas;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxPublisher publicador;
    @Autowired com.cafeorbe.auction.application.CierreDeSubastas cierre;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean WalletClient wallet;

    @BeforeEach
    void limpiar() {
        pujas.deleteAll();
        outbox.deleteAll();
        subastas.deleteAll();
        when(wallet.saldoDe(ANA)).thenReturn(OptionalLong.of(500));
        when(wallet.saldoDe(BRUNO)).thenReturn(OptionalLong.of(50));
    }

    // ── utilidades ─────────────────────────────────────────────────────────

    private static MockHttpServletRequestBuilder como(MockHttpServletRequestBuilder req, UUID id, String nombre, String rol) {
        return req.header(Cabeceras.USUARIO_ID, id.toString()).header(Cabeceras.USUARIO_NOMBRE, nombre)
                .header(Cabeceras.USUARIO_ROL, rol).contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder luis(MockHttpServletRequestBuilder r) {
        return como(r, LUIS, "Luis", "SUBASTADOR");
    }

    private MockHttpServletRequestBuilder ana(MockHttpServletRequestBuilder r) {
        return como(r, ANA, "Ana", "COMPRADOR");
    }

    private MockHttpServletRequestBuilder bruno(MockHttpServletRequestBuilder r) {
        return como(r, BRUNO, "Bruno", "COMPRADOR");
    }

    private String crearSubastaConFecha(String nombre, Instant fecha) {
        return "{\"nombre\":\"" + nombre + "\",\"descripcion\":\"d\",\"fechaInicio\":\"" + fecha + "\"}";
    }

    private String crearSubasta(String nombre) throws Exception {
        MvcResult res = mvc.perform(luis(post("/api/subastas"))
                        .content(crearSubastaConFecha(nombre, Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private void configurar(String id) throws Exception {
        mvc.perform(luis(put("/api/subastas/" + id + "/reglas"))
                        .content("{\"duracionMinutos\":10,\"precioBase\":100,\"incrementoMinimo\":10}"))
                .andExpect(status().isOk());
    }

    private String subastaEnCurso() throws Exception {
        String id = crearSubasta("Lote en vivo");
        configurar(id);
        mvc.perform(luis(post("/api/subastas/" + id + "/iniciar"))).andExpect(status().isOk());
        return id;
    }

    private List<String> tiposEnOutbox() {
        return outbox.findAll().stream().map(o -> o.getTipo()).toList();
    }

    // ── HU-08 · Crear subasta ─────────────────────────────────────────────

    @Test
    @DisplayName("HU-08 · Creación exitosa: queda Programada y aparece en el listado del Comprador")
    void creacionExitosa() throws Exception {
        crearSubasta("Lote 1");

        mvc.perform(ana(get("/api/subastas?estado=programada,en_curso")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("Lote 1"))
                .andExpect(jsonPath("$[0].estado").value("PROGRAMADA"));
    }

    @Test
    @DisplayName("HU-08 · Campos obligatorios: HTTP 400 y no crea la subasta")
    void camposObligatorios() throws Exception {
        mvc.perform(luis(post("/api/subastas")).content("{\"nombre\":\"\",\"fechaInicio\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").value("El nombre es obligatorio"))
                .andExpect(jsonPath("$.campos.fechaInicio").value("La fecha de inicio es obligatoria"));
        assertThat(subastas.count()).isZero();
    }

    @Test
    @DisplayName("HU-08 · Fecha en el pasado: La fecha de inicio debe ser futura")
    void fechaPasada() throws Exception {
        mvc.perform(luis(post("/api/subastas"))
                        .content(crearSubastaConFecha("Lote", Instant.now().minus(1, ChronoUnit.DAYS))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La fecha de inicio debe ser futura"))
                .andExpect(jsonPath("$.campos.fechaInicio").value("La fecha de inicio debe ser futura"));
        assertThat(subastas.count()).isZero();
    }

    @Test
    @DisplayName("Un Comprador no puede crear subastas (HTTP 403) y sin sesión responde 401")
    void soloElSubastadorCrea() throws Exception {
        mvc.perform(ana(post("/api/subastas"))
                        .content(crearSubastaConFecha("Lote", Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/subastas").contentType(MediaType.APPLICATION_JSON)
                        .content(crearSubastaConFecha("Lote", Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isUnauthorized());
    }

    // ── HU-03 y HU-04 · Homes ────────────────────────────────────────────

    @Test
    @DisplayName("HU-03 · /mias devuelve solo las subastas del Subastador y responde 403 a un Comprador")
    void subastasPropias() throws Exception {
        crearSubasta("De Luis");
        mvc.perform(como(post("/api/subastas"), MARTA, "Marta", "SUBASTADOR")
                        .content(crearSubastaConFecha("De Marta", Instant.now().plus(2, ChronoUnit.DAYS))))
                .andExpect(status().isCreated());

        mvc.perform(luis(get("/api/subastas/mias")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("De Luis"));
        mvc.perform(ana(get("/api/subastas/mias"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HU-04 · Sin subastas disponibles el listado viene vacío; el filtro por estado excluye otras")
    void listadoVacioYFiltro() throws Exception {
        mvc.perform(ana(get("/api/subastas?estado=programada,en_curso")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        crearSubasta("Programada");
        mvc.perform(ana(get("/api/subastas?estado=en_curso")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(ana(get("/api/subastas?estado=inventado"))).andExpect(status().isBadRequest());
    }

    // ── HU-09 · Ficha ────────────────────────────────────────────────────

    private static final String FICHA = """
            {"identificacion":"L-001","tipoCafe":"Arábica","pesoKg":450.5,"edadMeses":36,"observaciones":"Sana"}""";

    @Test
    @DisplayName("HU-09 · Registro de la ficha: queda asociada y es visible en el detalle")
    void registroDeFicha() throws Exception {
        String id = crearSubasta("Lote");
        mvc.perform(luis(put("/api/subastas/" + id + "/ficha")).content(FICHA)).andExpect(status().isOk());

        mvc.perform(ana(get("/api/subastas/" + id)))
                .andExpect(jsonPath("$.ficha.identificacion").value("L-001"))
                .andExpect(jsonPath("$.ficha.tipoCafe").value("Arábica"))
                .andExpect(jsonPath("$.ficha.pesoKg").value(450.5))
                .andExpect(jsonPath("$.ficha.edadMeses").value(36))
                .andExpect(jsonPath("$.ficha.observaciones").value("Sana"));
    }

    @Test
    @DisplayName("HU-09 · Edición bloqueada con la subasta iniciada: HTTP 409")
    void edicionBloqueada() throws Exception {
        String id = subastaEnCurso();
        mvc.perform(luis(put("/api/subastas/" + id + "/ficha")).content(FICHA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("La ficha no se puede editar con la subasta iniciada"));
    }

    @Test
    @DisplayName("Solo el dueño de la subasta puede editarla: otro Subastador recibe 403")
    void soloElDuenoEdita() throws Exception {
        String id = crearSubasta("Lote");
        mvc.perform(como(put("/api/subastas/" + id + "/ficha"), MARTA, "Marta", "SUBASTADOR").content(FICHA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HU-09 · Textos más largos que la columna: HTTP 400 junto al campo, no 500 (hallazgo 1)")
    void fichaConTextosLargos() throws Exception {
        String id = crearSubasta("Lote");
        String larga = FICHA.replace("\"L-001\"", "\"" + "X".repeat(101) + "\"");
        mvc.perform(luis(put("/api/subastas/" + id + "/ficha")).content(larga))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.identificacion").value("La identificación no puede superar 100 caracteres"));
        String observacionesLargas = FICHA.replace("\"Sana\"", "\"" + "o".repeat(1001) + "\"");
        mvc.perform(luis(put("/api/subastas/" + id + "/ficha")).content(observacionesLargas))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.observaciones").exists());
        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.ficha").doesNotExist());
    }

    @Test
    @DisplayName("HU-09 · Peso no positivo: el error del dominio llega junto al campo (hallazgo 3)")
    void fichaConPesoInvalido() throws Exception {
        String id = crearSubasta("Lote");
        mvc.perform(luis(put("/api/subastas/" + id + "/ficha")).content(FICHA.replace("450.5", "-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.pesoKg").value("El peso debe ser mayor que cero"));
    }

    // ── HU-10 · Reglas ───────────────────────────────────────────────────

    @Test
    @DisplayName("HU-10 · Configuración válida: se asocia a la subasta y queda versionada")
    void configuracionValida() throws Exception {
        String id = crearSubasta("Lote");
        configurar(id);
        mvc.perform(ana(get("/api/subastas/" + id)))
                .andExpect(jsonPath("$.reglas.duracionMinutos").value(10))
                .andExpect(jsonPath("$.reglas.precioBase").value(100))
                .andExpect(jsonPath("$.reglas.incrementoMinimo").value(10))
                .andExpect(jsonPath("$.reglas.version").value(1))
                .andExpect(jsonPath("$.siguienteMinimo").value(110));
    }

    @Test
    @DisplayName("HU-10 · Valores inválidos: Los valores deben ser mayores que cero")
    void valoresInvalidos() throws Exception {
        String id = crearSubasta("Lote");
        mvc.perform(luis(put("/api/subastas/" + id + "/reglas"))
                        .content("{\"duracionMinutos\":10,\"precioBase\":0,\"incrementoMinimo\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Los valores deben ser mayores que cero"))
                .andExpect(jsonPath("$.campos.precioBase").value("Los valores deben ser mayores que cero"));
        mvc.perform(luis(put("/api/subastas/" + id + "/reglas"))
                        .content("{\"duracionMinutos\":10,\"precioBase\":100,\"incrementoMinimo\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.incrementoMinimo").value("Los valores deben ser mayores que cero"));
        mvc.perform(luis(put("/api/subastas/" + id + "/reglas")).content("{\"duracionMinutos\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Los valores deben ser mayores que cero"));
        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.reglas").doesNotExist());
    }

    @Test
    @DisplayName("HU-10 · Decimales: HTTP 400 junto al campo en lugar de truncarse (hallazgo 2)")
    void reglasConDecimales() throws Exception {
        String id = crearSubasta("Lote");
        mvc.perform(luis(put("/api/subastas/" + id + "/reglas"))
                        .content("{\"duracionMinutos\":1.5,\"precioBase\":100,\"incrementoMinimo\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.duracionMinutos").value("Debe ser un número entero"));
        mvc.perform(luis(put("/api/subastas/" + id + "/reglas"))
                        .content("{\"duracionMinutos\":10,\"precioBase\":0.5,\"incrementoMinimo\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.precioBase").value("Debe ser un número entero"));
        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.reglas").doesNotExist());
    }

    // ── HU-12 · Iniciar ──────────────────────────────────────────────────

    @Test
    @DisplayName("HU-12 · Inicio: En curso, hora de fin persistida y evento SubastaIniciada en la outbox")
    void inicio() throws Exception {
        String id = subastaEnCurso();
        mvc.perform(ana(get("/api/subastas/" + id)))
                .andExpect(jsonPath("$.estado").value("EN_CURSO"))
                .andExpect(jsonPath("$.horaInicio").isNotEmpty())
                .andExpect(jsonPath("$.horaFin").isNotEmpty())
                .andExpect(jsonPath("$.precioActual").value(100));
        assertThat(tiposEnOutbox()).containsExactly(Eventos.SUBASTA_INICIADA);
    }

    @Test
    @DisplayName("HU-12 · Inicio sin configuración: HTTP 409 y no cambia el estado")
    void inicioSinConfiguracion() throws Exception {
        String id = crearSubasta("Lote");
        mvc.perform(luis(post("/api/subastas/" + id + "/iniciar")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Configura primero el tiempo y las reglas de puja"));
        assertThat(outbox.count()).isZero();
        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.estado").value("PROGRAMADA"));
    }

    // ── HU-13 y HU-14 · Pujas ────────────────────────────────────────────

    private MockHttpServletRequestBuilder pujarComoAna(String id, long monto) {
        return ana(post("/api/subastas/" + id + "/pujas")).content("{\"monto\":" + monto + "}");
    }

    private MockHttpServletRequestBuilder pujarComoBruno(String id, long monto) {
        return bruno(post("/api/subastas/" + id + "/pujas")).content("{\"monto\":" + monto + "}");
    }

    @Test
    @DisplayName("HU-13 y HU-14 · Puja válida: se registra, actualiza precio y líder y publica PujaAceptada")
    void pujaValida() throws Exception {
        String id = subastaEnCurso();

        mvc.perform(pujarComoAna(id, 110))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptada").value(true))
                .andExpect(jsonPath("$.monto").value(110))
                .andExpect(jsonPath("$.siguienteMinimo").value(120));

        mvc.perform(ana(get("/api/subastas/" + id)))
                .andExpect(jsonPath("$.precioActual").value(110))
                .andExpect(jsonPath("$.lider.nombre").value("Ana"))
                .andExpect(jsonPath("$.cantidadPujas").value(1));
        mvc.perform(ana(get("/api/subastas/" + id + "/pujas")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].usuarioNombre").value("Ana"))
                .andExpect(jsonPath("$[0].monto").value(110))
                .andExpect(jsonPath("$[0].creadaEn").isNotEmpty());
        assertThat(tiposEnOutbox()).containsExactly(Eventos.SUBASTA_INICIADA, Eventos.PUJA_ACEPTADA);
    }

    @Test
    @DisplayName("HU-13 · Siendo líder el botón queda bloqueado: 422 YA_ERES_LIDER (Vas ganando)")
    void liderNoRepite() throws Exception {
        String id = subastaEnCurso();
        mvc.perform(pujarComoAna(id, 110)).andExpect(status().isOk());

        mvc.perform(pujarComoAna(id, 120))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.aceptada").value(false))
                .andExpect(jsonPath("$.motivo").value("YA_ERES_LIDER"))
                .andExpect(jsonPath("$.mensaje").value("Vas ganando"));
    }

    @Test
    @DisplayName("HU-13 y HU-14 · Saldo insuficiente: 422 con Orbes insuficientes, no cambia el líder y publica PujaRechazada")
    void saldoInsuficiente() throws Exception {
        String id = subastaEnCurso();

        mvc.perform(pujarComoBruno(id, 110))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.motivo").value("SALDO_INSUFICIENTE"))
                .andExpect(jsonPath("$.mensaje").value("Orbes insuficientes"));

        mvc.perform(ana(get("/api/subastas/" + id)))
                .andExpect(jsonPath("$.lider").doesNotExist())
                .andExpect(jsonPath("$.precioActual").value(100));
        assertThat(pujas.count()).isZero();
        assertThat(tiposEnOutbox()).containsExactly(Eventos.SUBASTA_INICIADA, Eventos.PUJA_RECHAZADA);
    }

    @Test
    @DisplayName("HU-14 · Puja inferior al incremento: No cumple el incremento mínimo")
    void incrementoMinimo() throws Exception {
        String id = subastaEnCurso();
        mvc.perform(pujarComoAna(id, 105))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.motivo").value("INCREMENTO_MINIMO"))
                .andExpect(jsonPath("$.mensaje").value("No cumple el incremento mínimo"));
    }

    @Test
    @DisplayName("HU-14 · Si wallet no responde no se acepta la puja ni cambia la subasta")
    void walletCaido() throws Exception {
        String id = subastaEnCurso();
        when(wallet.saldoDe(ANA)).thenReturn(OptionalLong.empty());

        mvc.perform(pujarComoAna(id, 110))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.motivo").value("SALDO_NO_DISPONIBLE"));
        assertThat(pujas.count()).isZero();
    }

    @Test
    @DisplayName("Solo un Comprador puede pujar y la subasta debe existir")
    void quienPuedePujar() throws Exception {
        String id = subastaEnCurso();
        mvc.perform(luis(post("/api/subastas/" + id + "/pujas")).content("{\"monto\":110}"))
                .andExpect(status().isForbidden());
        mvc.perform(pujarComoAna(UUID.randomUUID().toString(), 110)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Historial: las pujas se consultan en orden, de la más reciente a la más antigua")
    void historialOrdenado() throws Exception {
        when(wallet.saldoDe(BRUNO)).thenReturn(OptionalLong.of(1000));
        String id = subastaEnCurso();
        mvc.perform(pujarComoAna(id, 110)).andExpect(status().isOk());
        mvc.perform(pujarComoBruno(id, 120)).andExpect(status().isOk());
        mvc.perform(pujarComoAna(id, 130)).andExpect(status().isOk());

        mvc.perform(ana(get("/api/subastas/" + id + "/pujas")))
                .andExpect(jsonPath("$[0].monto").value(130))
                .andExpect(jsonPath("$[1].monto").value(120))
                .andExpect(jsonPath("$[2].monto").value(110));
    }

    // ── Outbox ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Outbox · El publicador envía los eventos pendientes al exchange y los marca como publicados")
    void publicadorDeOutbox() throws Exception {
        String id = subastaEnCurso();
        mvc.perform(pujarComoAna(id, 110)).andExpect(status().isOk());
        assertThat(outbox.countByPublicadoEnIsNull()).isEqualTo(2);

        int publicados = publicador.publicarPendientes();

        assertThat(publicados).isEqualTo(2);
        assertThat(outbox.countByPublicadoEnIsNull()).isZero();
        var mensaje = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(Eventos.EXCHANGE), eq(Eventos.SUBASTA_INICIADA), mensaje.capture());
        JsonNode sobre = json.readTree(mensaje.getValue().getBody());
        assertThat(sobre.get("tipo").asText()).isEqualTo(Eventos.SUBASTA_INICIADA);
        assertThat(sobre.get("datos").get("subastaId").asText()).isEqualTo(id);
        assertThat(sobre.get("datos").get("precioBase").asLong()).isEqualTo(100);
        verify(rabbit).send(eq(Eventos.EXCHANGE), eq(Eventos.PUJA_ACEPTADA), any(Message.class));
    }

    @Test
    @DisplayName("Outbox · Si el broker falla el evento sigue pendiente y se reintenta después")
    void brokerCaidoSeReintenta() throws Exception {
        subastaEnCurso();
        Mockito.doThrow(new org.springframework.amqp.AmqpConnectException(new RuntimeException("caído")))
                .when(rabbit).send(any(String.class), any(String.class), any(Message.class));

        assertThat(publicador.publicarPendientes()).isZero();
        assertThat(outbox.countByPublicadoEnIsNull()).isEqualTo(1);

        Mockito.reset(rabbit);
        assertThat(publicador.publicarPendientes()).isEqualTo(1);
        assertThat(outbox.countByPublicadoEnIsNull()).isZero();
    }

    // ── Sprint 2 ───────────────────────────────────────────────────────────

    /** Datos del último evento de un tipo guardado en la outbox. */
    private JsonNode datosDelEvento(String tipo) throws Exception {
        var evento = outbox.findAll().stream().filter(o -> o.getTipo().equals(tipo)).reduce((a, b) -> b).orElseThrow();
        return json.readTree(evento.getPayload()).get("datos");
    }

    private void pujar(MockHttpServletRequestBuilder quien, long monto) throws Exception {
        mvc.perform(quien.content("{\"monto\":" + monto + "}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("HU-17 · El detalle entrega la hora del servidor, la hora de fin y el tiempo restante")
    void tiempoDelServidor() throws Exception {
        String id = subastaEnCurso();

        MvcResult res = mvc.perform(ana(get("/api/subastas/" + id))).andExpect(status().isOk()).andReturn();
        JsonNode d = json.readTree(res.getResponse().getContentAsString());

        assertThat(d.get("horaServidor").asText()).isNotBlank();
        assertThat(d.get("segundosRestantes").asLong()).isBetween(590L, 600L);
        assertThat(Instant.parse(d.get("horaFin").asText())).isAfter(Instant.parse(d.get("horaServidor").asText()));
        // Una subasta que no está en curso no tiene tiempo restante.
        String programada = crearSubasta("Aún sin iniciar");
        mvc.perform(ana(get("/api/subastas/" + programada))).andExpect(jsonPath("$.segundosRestantes").doesNotExist());
    }

    @Test
    @DisplayName("HU-18 · Una puja válida en la ventana final extiende el tiempo 30 s y deja TiempoExtendido en la outbox")
    void antiSniping() throws Exception {
        String id = subastaEnCurso();
        // Se adelanta la hora de fin para que queden 15 s: la subasta mínima dura un minuto.
        Instant fin = Instant.now().plusSeconds(15).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("update subasta set hora_fin = ? where id = ?", java.sql.Timestamp.from(fin), UUID.fromString(id));

        pujar(ana(post("/api/subastas/" + id + "/pujas")), 110);

        assertThat(tiposEnOutbox()).containsSubsequence(Eventos.PUJA_ACEPTADA, Eventos.TIEMPO_EXTENDIDO);
        JsonNode extension = datosDelEvento(Eventos.TIEMPO_EXTENDIDO);
        assertThat(extension.get("segundosExtendidos").asInt()).isEqualTo(30);
        assertThat(extension.get("extension").asInt()).isEqualTo(1);
        assertThat(Instant.parse(extension.get("horaFin").asText())).isEqualTo(fin.plusSeconds(30));
        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.extensiones").value(1));
    }

    @Test
    @DisplayName("HU-18 · Una puja lejos del final no extiende el tiempo")
    void sinExtensionLejosDelFinal() throws Exception {
        String id = subastaEnCurso();
        pujar(ana(post("/api/subastas/" + id + "/pujas")), 110);
        assertThat(tiposEnOutbox()).doesNotContain(Eventos.TIEMPO_EXTENDIDO);
    }

    @Test
    @DisplayName("HU-19 · Cierre con ganador: Finalizada, SubastaCerrada con el ganador y las pujas tardías se rechazan")
    void cierreConGanador() throws Exception {
        String id = subastaEnCurso();
        pujar(ana(post("/api/subastas/" + id + "/pujas")), 110);

        // Mientras no venza el tiempo, el cierre no toca la subasta.
        assertThat(cierre.cerrarVencidas(Instant.now())).isZero();
        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.estado").value("EN_CURSO"));

        assertThat(cierre.cerrarVencidas(Instant.now().plus(11, ChronoUnit.MINUTES))).isEqualTo(1);

        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.estado").value("FINALIZADA"));
        JsonNode cerrada = datosDelEvento(Eventos.SUBASTA_CERRADA);
        assertThat(cerrada.get("estado").asText()).isEqualTo("FINALIZADA");
        assertThat(cerrada.get("ganadorId").asText()).isEqualTo(ANA.toString());
        assertThat(cerrada.get("ganadorNombre").asText()).isEqualTo("Ana");
        assertThat(cerrada.get("montoFinal").asLong()).isEqualTo(110);
        // Cerrar otra vez no hace nada: un solo evento por subasta.
        assertThat(cierre.cerrarVencidas(Instant.now().plus(12, ChronoUnit.MINUTES))).isZero();
        assertThat(tiposEnOutbox().stream().filter(Eventos.SUBASTA_CERRADA::equals)).hasSize(1);

        when(wallet.saldoDe(BRUNO)).thenReturn(OptionalLong.of(500));
        mvc.perform(bruno(post("/api/subastas/" + id + "/pujas")).content("{\"monto\":120}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.motivo").value("SUBASTA_FINALIZADA"))
                .andExpect(jsonPath("$.mensaje").value("La subasta ya finalizó"));
    }

    @Test
    @DisplayName("HU-19 · Cierre sin pujas: queda Desierta y el evento no trae ganador")
    void cierreSinPujas() throws Exception {
        String id = subastaEnCurso();

        assertThat(cierre.cerrarVencidas(Instant.now().plus(11, ChronoUnit.MINUTES))).isEqualTo(1);

        mvc.perform(ana(get("/api/subastas/" + id))).andExpect(jsonPath("$.estado").value("DESIERTA"));
        JsonNode cerrada = datosDelEvento(Eventos.SUBASTA_CERRADA);
        assertThat(cerrada.get("estado").asText()).isEqualTo("DESIERTA");
        assertThat(cerrada.get("ganadorId").isNull()).isTrue();
        assertThat(cerrada.get("montoFinal").isNull()).isTrue();
    }

    @Test
    @DisplayName("HU-22 · Resultados: lote, ganador, monto final, cantidad de pujas e historial, iguales para ambos roles")
    void resultados() throws Exception {
        String id = subastaEnCurso();
        when(wallet.saldoDe(BRUNO)).thenReturn(OptionalLong.of(500));
        pujar(ana(post("/api/subastas/" + id + "/pujas")), 110);
        pujar(bruno(post("/api/subastas/" + id + "/pujas")), 120);

        // Antes del cierre todavía no hay resultados.
        mvc.perform(ana(get("/api/subastas/" + id + "/resultados"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("La subasta aún no ha finalizado"));

        cierre.cerrarVencidas(Instant.now().plus(11, ChronoUnit.MINUTES));

        for (var quien : List.of(ana(get("/api/subastas/" + id + "/resultados")), luis(get("/api/subastas/" + id + "/resultados")))) {
            mvc.perform(quien).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nombre").value("Lote en vivo"))
                    .andExpect(jsonPath("$.estado").value("FINALIZADA"))
                    .andExpect(jsonPath("$.ganador.nombre").value("Bruno"))
                    .andExpect(jsonPath("$.montoFinal").value(120))
                    .andExpect(jsonPath("$.cantidadPujas").value(2))
                    .andExpect(jsonPath("$.ultimasPujas.length()").value(2))
                    .andExpect(jsonPath("$.ultimasPujas[0].monto").value(120))
                    .andExpect(jsonPath("$.ultimasPujas[0].creadaEn").isNotEmpty());
        }
        mvc.perform(ana(get("/api/subastas/" + UUID.randomUUID() + "/resultados"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("HU-22 · Resultados de una subasta desierta: sin ganador ni monto final")
    void resultadosDesierta() throws Exception {
        String id = subastaEnCurso();
        cierre.cerrarVencidas(Instant.now().plus(11, ChronoUnit.MINUTES));

        mvc.perform(ana(get("/api/subastas/" + id + "/resultados"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DESIERTA"))
                .andExpect(jsonPath("$.ganador").doesNotExist())
                .andExpect(jsonPath("$.montoFinal").doesNotExist())
                .andExpect(jsonPath("$.cantidadPujas").value(0));
    }

    // ── Hallazgo 2: un decimal en un campo entero ya no se trunca en silencio ──

    @Test
    @DisplayName("Hallazgo 2 · Un decimal donde va un entero se rechaza y señala el campo, no se trunca")
    void decimalEnCampoEntero() throws Exception {
        mvc.perform(ana(post("/api/subastas/" + UUID.randomUUID() + "/pujas")).content("{\"monto\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Debe ser un número entero"))
                .andExpect(jsonPath("$.campos.monto").value("Debe ser un número entero"));
    }

    @Test
    @DisplayName("Hallazgo 2 · Un texto donde va un entero también se rechaza con el campo señalado")
    void textoEnCampoEntero() throws Exception {
        mvc.perform(ana(post("/api/subastas/" + UUID.randomUUID() + "/pujas")).content("{\"monto\":\"mucho\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.monto").value("Debe ser un número entero"));
    }

    @Test
    @DisplayName("Un cuerpo ilegible sin campo localizable responde 400 genérico, no filtra internals")
    void cuerpoIlegible() throws Exception {
        mvc.perform(ana(post("/api/subastas/" + UUID.randomUUID() + "/pujas")).content("esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").value("La solicitud no es válida"))
                .andExpect(jsonPath("$.campos").isEmpty());
    }

    @Test
    @DisplayName("Un id de subasta que no es UUID en la ruta responde 400 con el formato uniforme")
    void idNoEsUuidEnLaRuta() throws Exception {
        mvc.perform(luis(get("/api/subastas/no-es-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").value("La solicitud no es válida"));
    }

    // ── HU-04 · Listar y filtrar, y 404s de consulta ───────────────────────

    @Test
    @DisplayName("HU-04 · Sin filtro devuelve todas las subastas, y una sin reglas no inventa un precio")
    void listarSinFiltroDevuelveTodas() throws Exception {
        crearSubasta("Lote sin configurar");
        subastaEnCurso();

        var cuerpo = json.readTree(mvc.perform(ana(get("/api/subastas")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(cuerpo).hasSize(2);
        assertThat(cuerpo.findValuesAsText("estado")).containsExactlyInAnyOrder("PROGRAMADA", "EN_CURSO");
        for (var resumen : cuerpo) {
            if ("PROGRAMADA".equals(resumen.get("estado").asText())) {
                assertThat(resumen.get("precioActual").isNull()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("HU-04 · Un filtro de estado en blanco o ausente equivale a no filtrar")
    void filtroEnBlancoEquivaleAFiltrarTodo() throws Exception {
        crearSubasta("Lote sin configurar");
        subastaEnCurso();

        mvc.perform(ana(get("/api/subastas").param("estado", "   ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("HU-04 · El filtro por estado devuelve solo las subastas en ese estado")
    void filtroPorEstado() throws Exception {
        crearSubasta("Lote sin configurar");
        subastaEnCurso();

        mvc.perform(ana(get("/api/subastas").param("estado", "en_curso")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].estado").value("EN_CURSO"));
    }

    @Test
    @DisplayName("Un estado que no existe se rechaza con 400, no se ignora en silencio")
    void estadoInvalido() throws Exception {
        mvc.perform(ana(get("/api/subastas").param("estado", "inventado")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("El detalle de una subasta inexistente responde 404 con el mensaje uniforme")
    void detalleInexistente() throws Exception {
        mvc.perform(ana(get("/api/subastas/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.mensaje").value("La subasta no existe"));
    }

    @Test
    @DisplayName("El historial de una subasta inexistente responde 404, no una lista vacía")
    void historialDeSubastaInexistente() throws Exception {
        mvc.perform(ana(get("/api/subastas/" + UUID.randomUUID() + "/pujas")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("La subasta no existe"));
    }
}
