package com.cafeorbe.auction.api;

import com.cafeorbe.auction.application.RegistrarPujaService;
import com.cafeorbe.auction.domain.ResultadoPuja;
import com.cafeorbe.contracts.dto.PujaSolicitud;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PujaController {

    /**
     * Respuesta de una puja. Aceptada: HTTP 200. Rechazada: HTTP 422 con motivo tipificado y mensaje.
     * El resto de los conectados se entera por el evento PujaAceptada, no por esta respuesta.
     */
    public record PujaRespuesta(boolean aceptada, UUID pujaId, long monto, Long siguienteMinimo,
                                String motivo, String mensaje) {
    }

    private final RegistrarPujaService pujas;

    public PujaController(RegistrarPujaService pujas) {
        this.pujas = pujas;
    }

    /** HU-13 y HU-14: la llama el realtime-gateway con la puja que llegó por WebSocket (o directo por REST). */
    @PostMapping("/api/subastas/{id}/pujas")
    public ResponseEntity<PujaRespuesta> pujar(UsuarioActual usuario, @PathVariable UUID id,
                                               @RequestBody PujaSolicitud solicitud) {
        var registro = pujas.registrar(id, usuario, solicitud.monto());
        return switch (registro.resultado()) {
            case ResultadoPuja.Aceptada a -> ResponseEntity.ok(new PujaRespuesta(true, a.puja().getId(),
                    a.puja().getMonto(), registro.siguienteMinimo(), null, null));
            case ResultadoPuja.Rechazada r -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new PujaRespuesta(false, null, r.montoIntentado(), registro.siguienteMinimo(),
                            r.motivo().name(), r.motivo().mensaje()));
        };
    }
}
