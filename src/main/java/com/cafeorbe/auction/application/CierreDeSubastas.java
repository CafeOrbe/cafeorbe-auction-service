package com.cafeorbe.auction.application;

import com.cafeorbe.auction.domain.EstadoSubasta;
import com.cafeorbe.auction.domain.Subasta;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import com.cafeorbe.auction.infrastructure.outbox.OutboxWriter;
import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.eventos.SubastaCerrada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * HU-19: cierra las subastas cuyo tiempo terminó. Lo ejecuta el servidor, así que la subasta cierra
 * aunque nadie tenga la sala abierta. El cambio de estado y el evento SubastaCerrada se guardan en la
 * misma transacción; wallet cobra al ganador (HU-20) y la sala lo anuncia (HU-21) a partir de ese evento.
 */
@Service
public class CierreDeSubastas {

    private static final Logger log = LoggerFactory.getLogger(CierreDeSubastas.class);
    private static final int TAMANO_LOTE = 50;

    private final SubastaRepository subastas;
    private final OutboxWriter outbox;

    public CierreDeSubastas(SubastaRepository subastas, OutboxWriter outbox) {
        this.subastas = subastas;
        this.outbox = outbox;
    }

    /** @return cantidad de subastas cerradas en esta pasada */
    @Transactional
    public int cerrarVencidas(Instant ahora) {
        var vencidas = subastas.vencidas(ahora, PageRequest.of(0, TAMANO_LOTE));
        for (Subasta subasta : vencidas) {
            subasta.cerrar(ahora);
            boolean conGanador = subasta.getEstado() == EstadoSubasta.FINALIZADA;
            outbox.registrar(Eventos.SUBASTA_CERRADA, new SubastaCerrada(subasta.getId(), subasta.getNombre(),
                    subasta.getEstado().name(),
                    conGanador ? subasta.getLiderId() : null,
                    conGanador ? subasta.getLiderNombre() : null,
                    conGanador ? subasta.precioActualOBase() : null,
                    subasta.getCantidadPujas(), ahora));
            log.info("Subasta {} cerrada: {} ({} pujas)", subasta.getId(), subasta.getEstado(), subasta.getCantidadPujas());
        }
        return vencidas.size();
    }
}
