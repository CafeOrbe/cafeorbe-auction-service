package com.cafeorbe.auction.infrastructure.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvento, Long> {

    /**
     * Eventos sin publicar, en orden, bloqueados para esta pasada.
     *
     * <p>REN-01 y DIS-01: con varias réplicas de auction, el bloqueo <em>espera</em> en lugar de saltar filas
     * (sin SKIP LOCKED). Si una réplica está publicando un lote, la otra queda esperando la fila más antigua y,
     * cuando la primera confirma, ya no la ve pendiente y sigue con las posteriores. Así los eventos salen al
     * broker de a una réplica por vez y en orden de id (la secuencia que usa el navegador), y ninguno se publica
     * dos veces. Con SKIP LOCKED la segunda réplica podía publicar eventos nuevos antes de que la primera
     * terminara su lote, y el navegador descartaba los que llegaban con secuencia menor.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvento o where o.publicadoEn is null order by o.id asc")
    List<OutboxEvento> pendientes(Pageable pagina);

    long countByPublicadoEnIsNull();
}
