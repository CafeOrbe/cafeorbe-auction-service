package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.auction.domain.EstadoSubasta;
import com.cafeorbe.auction.domain.Subasta;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
