package com.cafeorbe.auction.api;

import com.cafeorbe.auction.application.ConsultasDeSubasta;
import com.cafeorbe.auction.application.SubastaService;
import com.cafeorbe.auction.application.Vistas.Detalle;
import com.cafeorbe.auction.application.Vistas.PujaVista;
import com.cafeorbe.auction.application.Vistas.Resumen;
import com.cafeorbe.auction.domain.EstadoSubasta;
import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import com.cafeorbe.contracts.Rol;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/subastas")
public class SubastaController {

    private final SubastaService comandos;
    private final ConsultasDeSubasta consultas;

    public SubastaController(SubastaService comandos, ConsultasDeSubasta consultas) {
        this.comandos = comandos;
        this.consultas = consultas;
    }

    /** HU-08 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Detalle crear(UsuarioActual usuario, @Valid @RequestBody Solicitudes.CrearSubasta datos) {
        return consultas.detalle(comandos.crear(usuario, datos));
    }

    /** HU-04: {@code ?estado=programada,en_curso}. Sin filtro devuelve todas. */
    @GetMapping
    public List<Resumen> listar(UsuarioActual usuario, @RequestParam(name = "estado", required = false) String estado) {
        return consultas.listar(parsearEstados(estado));
    }

    /** HU-03: solo el Subastador ve sus propias subastas. */
    @GetMapping("/mias")
    public List<Resumen> mias(UsuarioActual usuario) {
        usuario.exigirRol(Rol.SUBASTADOR);
        return consultas.delSubastador(usuario.id());
    }

    @GetMapping("/{id}")
    public Detalle detalle(UsuarioActual usuario, @PathVariable UUID id) {
        return consultas.detalle(id);
    }

    /** HU-09 */
    @PutMapping("/{id}/ficha")
    public Detalle registrarFicha(UsuarioActual usuario, @PathVariable UUID id,
                                  @Valid @RequestBody Solicitudes.RegistrarFicha datos) {
        comandos.registrarFicha(id, usuario, datos);
        return consultas.detalle(id);
    }

    /** HU-10 */
    @PutMapping("/{id}/reglas")
    public Detalle configurarReglas(UsuarioActual usuario, @PathVariable UUID id,
                                    @Valid @RequestBody Solicitudes.ConfigurarReglas datos) {
        comandos.configurarReglas(id, usuario, datos);
        return consultas.detalle(id);
    }

    /** HU-12 */
    @PostMapping("/{id}/iniciar")
    public Detalle iniciar(UsuarioActual usuario, @PathVariable UUID id) {
        comandos.iniciar(id, usuario);
        return consultas.detalle(id);
    }

    /** HU-14: historial de pujas aceptadas, de la más reciente a la más antigua. */
    @GetMapping("/{id}/pujas")
    public List<PujaVista> historial(UsuarioActual usuario, @PathVariable UUID id) {
        return consultas.historial(id, 100);
    }

    private static Set<EstadoSubasta> parsearEstados(String estado) {
        if (estado == null || estado.isBlank()) {
            return EnumSet.noneOf(EstadoSubasta.class);
        }
        try {
            return Arrays.stream(estado.split(","))
                    .map(e -> EstadoSubasta.valueOf(e.trim().toUpperCase()))
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(EstadoSubasta.class)));
        } catch (IllegalArgumentException e) {
            throw ReglaDeNegocioException.validacion("Estado inválido: use programada, en_curso, finalizada o desierta");
        }
    }
}
