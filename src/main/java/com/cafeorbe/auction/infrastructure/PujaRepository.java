package com.cafeorbe.auction.infrastructure;

import com.cafeorbe.auction.domain.Puja;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PujaRepository extends JpaRepository<Puja, UUID> {

    /** Cada puja aceptada supera a la anterior, así que ordenar por monto equivale a ordenar por tiempo. */
    List<Puja> findBySubastaIdOrderByMontoDesc(UUID subastaId, Pageable pagina);
}
