package com.arb.repository;

import com.arb.model.ChainEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChainEventRepository extends JpaRepository<ChainEvent, Long> {

    List<ChainEvent> findByMarketIdOrderByTsDesc(String marketId);

    List<ChainEvent> findByEventTypeOrderByTsDesc(String eventType);

    boolean existsByTxHash(String txHash);
}
