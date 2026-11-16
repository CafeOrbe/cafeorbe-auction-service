package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.auction.seguridad.FiltroDeCorrelacion;
import com.cafeorbe.auction.seguridad.FiltroDeSeguridadInterna;
import com.cafeorbe.contracts.dto.SaldoDto;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Única llamada síncrona entre servicios: consulta el saldo de Orbes en wallet (HU-14). */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);
    /** REN-01: TTL de caché de saldo en memoria para soportar hasta 20 pujas/s sin saturar wallet. */
    private static final long TTL_SALDO_NANOS = Duration.ofSeconds(10).toNanos();

    private record EntradaCache(long saldo, long venceNanos) {}
    private final Map<UUID, EntradaCache> cache = new ConcurrentHashMap<>();

    private final RestClient cliente;

    public WalletClient(@Value("${cafeorbe.wallet.url}") String url,
                        @Value("${cafeorbe.wallet.connect-timeout-ms}") int conexionMs,
                        @Value("${cafeorbe.wallet.read-timeout-ms}") int lecturaMs,
                        @Value("${cafeorbe.seguridad.credencial-interna}") String credencial) {
        var fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(conexionMs);
        fabrica.setReadTimeout(lecturaMs);
        // SEG-02: wallet exige la credencial interna en /internal/**.
        this.cliente = RestClient.builder().baseUrl(url).requestFactory(fabrica)
                .defaultHeader(FiltroDeSeguridadInterna.CABECERA, credencial)
                .requestInterceptor((peticion, cuerpo, ejecucion) -> {
                    // OBS-01: la consulta de saldo lleva el correlationId de la puja.
                    String correlacion = MDC.get(FiltroDeCorrelacion.MDC_CLAVE);
                    if (correlacion != null) {
                        peticion.getHeaders().set(FiltroDeCorrelacion.CABECERA, correlacion);
                    }
                    return ejecucion.execute(peticion, cuerpo);
                })
                .build();
    }

    /** Saldo del usuario, o vacío si wallet no respondió (la puja se rechaza sin cambiar nada). */
    public OptionalLong saldoDe(UUID usuarioId) {
        long ahora = System.nanoTime();
        EntradaCache entrada = cache.get(usuarioId);
        if (entrada != null && ahora < entrada.venceNanos()) {
            return OptionalLong.of(entrada.saldo());
        }
        try {
            SaldoDto dto = cliente.get().uri("/internal/orbes/{id}/saldo", usuarioId).retrieve().body(SaldoDto.class);
            if (dto != null) {
                cache.put(usuarioId, new EntradaCache(dto.saldo(), ahora + TTL_SALDO_NANOS));
                return OptionalLong.of(dto.saldo());
            }
            return OptionalLong.empty();
        } catch (RestClientException e) {
            if (entrada != null) {
                return OptionalLong.of(entrada.saldo());
            }
            log.warn("No se pudo consultar el saldo de {} en wallet: {}", usuarioId, e.getMessage());
            return OptionalLong.empty();
        }
    }

    void invalidar(UUID usuarioId) {
        cache.remove(usuarioId);
    }
}
