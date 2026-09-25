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
}
