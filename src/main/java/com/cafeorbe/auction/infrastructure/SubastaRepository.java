package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.auction.domain.EstadoSubasta;
import com.cafeorbe.auction.domain.Subasta;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubastaRepository extends JpaRepository<Subasta, UUID> {

    List<Subasta> findByEstadoInOrderByFechaInicioAsc(Collection<EstadoSubasta> estados);

    List<Subasta> findBySubastadorIdOrderByFechaInicioDesc(UUID subastadorId);

    /** Bloquea la fila para serializar las pujas concurrentes sobre la misma subasta. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subasta s where s.id = :id")
    Optional<Subasta> findByIdForUpdate(@Param("id") UUID id);

    /**
     * HU-19: subastas En curso cuya hora de fin ya pasó. Las bloquea para cerrarlas; SKIP LOCKED (-2) deja
     * que varias instancias cierren a la vez sin procesar dos veces la misma, y sin esperar a una puja en curso.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select s from Subasta s where s.estado = com.cafeorbe.auction.domain.EstadoSubasta.EN_CURSO "
            + "and s.horaFin <= :ahora order by s.horaFin asc")
    List<Subasta> vencidas(@Param("ahora") Instant ahora, Pageable pagina);
}
