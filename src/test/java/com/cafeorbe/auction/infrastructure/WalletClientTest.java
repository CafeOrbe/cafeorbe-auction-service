package com.cafeorbe.auction.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Única llamada síncrona entre servicios: antes de aceptar una puja, auction pregunta a wallet cuánto
 * tiene el comprador. Si wallet no responde, saldoDe devuelve vacío y la puja se rechaza sin tocar nada,
 * así que el caso de wallet caído es tan importante como el del saldo normal.
 */
class WalletClientTest {

    static final UUID ANA = UUID.randomUUID();

    private static HttpServer wallet;
    private static int puertoCaido;
    private static final AtomicInteger codigo = new AtomicInteger(200);
    private static final AtomicReference<String> cuerpo = new AtomicReference<>("");
    private static final AtomicReference<String> ruta = new AtomicReference<>();

    @BeforeAll
    static void levantarWalletFalso() throws IOException {
        wallet = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        wallet.createContext("/", intercambio -> {
            ruta.set(intercambio.getRequestURI().getPath());
            byte[] bytes = cuerpo.get().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo.get(), bytes.length);
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(bytes);
            }
        });
        wallet.start();
        try (ServerSocket s = new ServerSocket(0)) {
            puertoCaido = s.getLocalPort();
        }
    }

    @AfterAll
    static void apagar() {
        wallet.stop(0);
    }

    private static WalletClient cliente() {
        return new WalletClient("http://localhost:" + wallet.getAddress().getPort(), 500, 500);
    }

    private static WalletClient caido() {
        return new WalletClient("http://localhost:" + puertoCaido, 300, 300);
    }

    @Test
    @DisplayName("Saldo suficiente: devuelve el saldo que wallet responde y consulta la ruta del usuario")
    void saldoSuficiente() {
        codigo.set(200);
        cuerpo.set("{\"usuarioId\":\"" + ANA + "\",\"saldo\":1000}");

        OptionalLong saldo = cliente().saldoDe(ANA);

        assertThat(saldo).isPresent().hasValue(1000L);
        assertThat(ruta.get()).isEqualTo("/internal/orbes/" + ANA + "/saldo");
    }

    @Test
    @DisplayName("Un saldo de cero no es ausencia de saldo: es un cero real y la puja se rechaza")
    void saldoCeroEsUnCeroReal() {
        codigo.set(200);
        cuerpo.set("{\"usuarioId\":\"" + ANA + "\",\"saldo\":0}");

        assertThat(cliente().saldoDe(ANA)).isPresent().hasValue(0L);
    }

    @Test
    @DisplayName("Wallet responde 200 sin cuerpo: vacío, para que la puja se rechace sin cambiar nada")
    void respuestaVacia() {
        codigo.set(200);
        cuerpo.set("");

        assertThat(cliente().saldoDe(ANA)).isEmpty();
    }

    @Test
    @DisplayName("Wallet caído: vacío y la puja se rechaza, nunca se acepta a ciegas")
    void walletCaido() {
        assertThat(caido().saldoDe(ANA)).isEmpty();
    }

    @Test
    @DisplayName("Wallet devuelve 500: vacío, sin propagar la excepción técnica al flujo de la puja")
    void walletConError() {
        codigo.set(500);
        cuerpo.set("{\"status\":500,\"mensaje\":\"boom\",\"campos\":{}}");

        assertThat(cliente().saldoDe(ANA)).isEmpty();
    }

    @Test
    @DisplayName("Wallet devuelve 404: también vacío; no es «saldo suficiente» por defecto")
    void walletConNotFound() {
        codigo.set(404);
        cuerpo.set("{\"status\":404,\"mensaje\":\"no\",\"campos\":{}}");

        assertThat(cliente().saldoDe(ANA)).isEmpty();
    }
}
