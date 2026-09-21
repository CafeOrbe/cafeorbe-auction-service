package com.cafeorbe.auction.application;

import com.cafeorbe.auction.api.UsuarioActual;
import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import com.cafeorbe.auction.domain.ResultadoPuja;
import com.cafeorbe.auction.domain.Subasta;
import com.cafeorbe.auction.infrastructure.PujaRepository;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import com.cafeorbe.auction.infrastructure.WalletClient;
import com.cafeorbe.auction.infrastructure.outbox.OutboxWriter;
import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.Rol;
import com.cafeorbe.contracts.eventos.PujaAceptada;
import com.cafeorbe.contracts.eventos.PujaRechazada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.OptionalLong;
import java.util.UUID;

/** HU-13 y HU-14: valida y registra una puja. */
@Service
public class RegistrarPujaService {

    /** Resultado del dominio más el estado de la subasta tras procesar la puja. */
    public record Registro(ResultadoPuja resultado, long siguienteMinimo, int cantidadPujas) {
    }

    private final SubastaRepository subastas;
    private final PujaRepository pujas;
    private final WalletClient wallet;
    private final OutboxWriter outbox;
    private final Clock reloj;
    private final TransactionTemplate transaccion;

    public RegistrarPujaService(SubastaRepository subastas, PujaRepository pujas, WalletClient wallet,
                                OutboxWriter outbox, Clock reloj, PlatformTransactionManager gestor) {
        this.subastas = subastas;
        this.pujas = pujas;
        this.wallet = wallet;
        this.outbox = outbox;
        this.reloj = reloj;
        this.transaccion = new TransactionTemplate(gestor);
    }

    public Registro registrar(UUID subastaId, UsuarioActual usuario, long monto) {
        usuario.exigirRol(Rol.COMPRADOR);

        // El saldo se consulta antes de bloquear la subasta para no sostener el bloqueo durante una llamada HTTP.
        OptionalLong saldo = wallet.saldoDe(usuario.id());

        return transaccion.execute(estado -> {
            Subasta subasta = subastas.findByIdForUpdate(subastaId)
                    .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("La subasta no existe"));

            ResultadoPuja resultado = subasta.pujar(usuario.id(), usuario.nombre(), monto, saldo, reloj.instant());
            long siguiente = subasta.reglas().isPresent() ? subasta.siguienteMinimo() : 0;

            switch (resultado) {
                case ResultadoPuja.Aceptada aceptada -> {
                    var puja = aceptada.puja();
                    pujas.save(puja);
                    subastas.save(subasta);
                    outbox.registrar(Eventos.PUJA_ACEPTADA, new PujaAceptada(subastaId, puja.getId(),
                            usuario.id(), usuario.nombre(), puja.getMonto(), subasta.getCantidadPujas(), siguiente,
                            puja.getCreadaEn()));
                }
                case ResultadoPuja.Rechazada rechazada -> outbox.registrar(Eventos.PUJA_RECHAZADA,
                        new PujaRechazada(subastaId, usuario.id(), rechazada.montoIntentado(),
                                rechazada.motivo().name(), rechazada.motivo().mensaje()));
            }
            return new Registro(resultado, siguiente, subasta.getCantidadPujas());
        });
    }
}
