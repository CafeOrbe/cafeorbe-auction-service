package com.cafeorbe.auction.application;

import com.cafeorbe.auction.application.Vistas.Detalle;
import com.cafeorbe.auction.application.Vistas.FichaVista;
import com.cafeorbe.auction.application.Vistas.LiderVista;
import com.cafeorbe.auction.application.Vistas.PujaVista;
import com.cafeorbe.auction.application.Vistas.ReglasVista;
import com.cafeorbe.auction.application.Vistas.Resumen;
import com.cafeorbe.auction.domain.EstadoSubasta;
import com.cafeorbe.auction.domain.Puja;
import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import com.cafeorbe.auction.domain.Subasta;
import com.cafeorbe.auction.infrastructure.PujaRepository;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Lado de consultas (CQRS ligero): lectura del lobby, del panel del Subastador y del detalle de la sala. */
@Service
@Transactional(readOnly = true)
public class ConsultasDeSubasta {

    private static final int ULTIMAS_PUJAS = 10;

    private final SubastaRepository subastas;
    private final PujaRepository pujas;

    public ConsultasDeSubasta(SubastaRepository subastas, PujaRepository pujas) {
        this.subastas = subastas;
        this.pujas = pujas;
    }

    /** HU-04: subastas por estado, ordenadas por fecha. Sin filtro devuelve todas. */
    public List<Resumen> listar(Collection<EstadoSubasta> estados) {
        Collection<EstadoSubasta> filtro = estados.isEmpty() ? EnumSet.allOf(EstadoSubasta.class) : estados;
        return subastas.findByEstadoInOrderByFechaInicioAsc(filtro).stream().map(ConsultasDeSubasta::resumen).toList();
    }

    /** HU-03: subastas creadas por el Subastador. */
    public List<Resumen> delSubastador(UUID subastadorId) {
        return subastas.findBySubastadorIdOrderByFechaInicioDesc(subastadorId).stream()
                .map(ConsultasDeSubasta::resumen).toList();
    }

    public Detalle detalle(UUID id) {
        Subasta s = subastas.findById(id).orElseThrow(() -> ReglaDeNegocioException.noEncontrado("La subasta no existe"));
        return detalle(s);
    }

    /** Historial de pujas aceptadas, de la más reciente a la más antigua. */
    public List<PujaVista> historial(UUID id, int limite) {
        if (!subastas.existsById(id)) {
            throw ReglaDeNegocioException.noEncontrado("La subasta no existe");
        }
        return pujas.findBySubastaIdOrderByMontoDesc(id, PageRequest.of(0, limite)).stream()
                .map(ConsultasDeSubasta::puja).toList();
    }

    private Detalle detalle(Subasta s) {
        var reglas = s.reglas();
        return new Detalle(
                s.getId(), s.getNombre(), s.getDescripcion(), s.getEstado(), s.getFechaInicio(),
                s.getSubastadorId(), s.getSubastadorNombre(),
                s.getFicha().map(f -> new FichaVista(f.identificacion(), f.tipoCafe(), f.pesoKg(), f.edadMeses(),
                        f.observaciones())).orElse(null),
                reglas.map(r -> new ReglasVista(r.duracionMinutos(), r.precioBase(), r.incrementoMinimo(),
                        s.getReglasVersion())).orElse(null),
                s.getHoraInicio(), s.getHoraFin(),
                reglas.isPresent() ? s.precioActualOBase() : null,
                reglas.isPresent() ? s.siguienteMinimo() : null,
                s.getLiderId() == null ? null : new LiderVista(s.getLiderId(), s.getLiderNombre()),
                s.getCantidadPujas(),
                pujas.findBySubastaIdOrderByMontoDesc(s.getId(), PageRequest.of(0, ULTIMAS_PUJAS)).stream()
                        .map(ConsultasDeSubasta::puja).toList());
    }

    private static Resumen resumen(Subasta s) {
        return new Resumen(s.getId(), s.getNombre(), s.getEstado(), s.getFechaInicio(), s.getSubastadorNombre(),
                s.reglas().isPresent() ? s.precioActualOBase() : null, s.getCantidadPujas());
    }

    private static PujaVista puja(Puja p) {
        return new PujaVista(p.getId(), p.getUsuarioId(), p.getUsuarioNombre(), p.getMonto(), p.getCreadaEn());
    }
}
