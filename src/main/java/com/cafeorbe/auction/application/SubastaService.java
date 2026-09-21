package com.cafeorbe.auction.application;

import com.cafeorbe.auction.api.Solicitudes;
import com.cafeorbe.auction.api.UsuarioActual;
import com.cafeorbe.auction.domain.FichaLote;
import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import com.cafeorbe.auction.domain.ReglasDePuja;
import com.cafeorbe.auction.domain.Subasta;
import com.cafeorbe.auction.infrastructure.SubastaRepository;
import com.cafeorbe.auction.infrastructure.outbox.OutboxWriter;
import com.cafeorbe.contracts.Eventos;
import com.cafeorbe.contracts.Rol;
import com.cafeorbe.contracts.eventos.SubastaIniciada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Lado de comandos: casos de uso que cambian la subasta. Las reglas viven en el agregado {@link Subasta}. */
@Service
public class SubastaService {

    private final SubastaRepository subastas;
    private final OutboxWriter outbox;
    private final Clock reloj;

    public SubastaService(SubastaRepository subastas, OutboxWriter outbox, Clock reloj) {
        this.subastas = subastas;
        this.outbox = outbox;
        this.reloj = reloj;
    }

    /** HU-08 */
    @Transactional
    public UUID crear(UsuarioActual usuario, Solicitudes.CrearSubasta datos) {
        usuario.exigirRol(Rol.SUBASTADOR);
        Subasta subasta = Subasta.programar(datos.nombre(), datos.descripcion(), datos.fechaInicio(),
                usuario.id(), usuario.nombre(), reloj.instant());
        return subastas.save(subasta).getId();
    }

    /** HU-09 */
    @Transactional
    public void registrarFicha(UUID id, UsuarioActual usuario, Solicitudes.RegistrarFicha datos) {
        Subasta subasta = cargarPropia(id, usuario);
        subasta.registrarFicha(new FichaLote(datos.identificacion(), datos.raza(), datos.pesoKg(),
                datos.edadMeses(), datos.observaciones()));
    }

    /** HU-10 */
    @Transactional
    public void configurarReglas(UUID id, UsuarioActual usuario, Solicitudes.ConfigurarReglas datos) {
        Subasta subasta = cargarPropia(id, usuario);
        subasta.configurarReglas(new ReglasDePuja(datos.duracionMinutos(), datos.precioBase(),
                datos.incrementoMinimo()));
    }

    /** HU-12: el cambio de estado y el evento SubastaIniciada se guardan en la misma transacción. */
    @Transactional
    public void iniciar(UUID id, UsuarioActual usuario) {
        Subasta subasta = cargarPropia(id, usuario);
        subasta.iniciar(reloj.instant());
        outbox.registrar(Eventos.SUBASTA_INICIADA, new SubastaIniciada(subasta.getId(), subasta.getNombre(),
                subasta.getPrecioBase(), subasta.getIncrementoMinimo(), subasta.getDuracionMinutos(),
                subasta.getHoraInicio(), subasta.getHoraFin()));
    }

    private Subasta cargarPropia(UUID id, UsuarioActual usuario) {
        usuario.exigirRol(Rol.SUBASTADOR);
        Subasta subasta = subastas.findById(id)
                .orElseThrow(() -> ReglaDeNegocioException.noEncontrado("La subasta no existe"));
        if (!subasta.esDe(usuario.id())) {
            throw ReglaDeNegocioException.prohibido("No autorizado");
        }
        return subasta;
    }
}
