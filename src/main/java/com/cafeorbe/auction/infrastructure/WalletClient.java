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

import java.util.OptionalLong;
import java.util.UUID;

/** Única llamada síncrona entre servicios: consulta el saldo de Orbes en wallet (HU-14). */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

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
        try {
            SaldoDto dto = cliente.get().uri("/internal/orbes/{id}/saldo", usuarioId).retrieve().body(SaldoDto.class);
            return dto == null ? OptionalLong.empty() : OptionalLong.of(dto.saldo());
        } catch (RestClientException e) {
            log.warn("No se pudo consultar el saldo de {} en wallet: {}", usuarioId, e.getMessage());
            return OptionalLong.empty();
        }
    }
}
