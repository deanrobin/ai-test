package com.arb.repository;

import com.arb.model.OkxSpotTicker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OkxSpotTickerRepository extends JpaRepository<OkxSpotTicker, Long> {

    Optional<OkxSpotTicker> findTopBySymbolOrderByTsDesc(String symbol);
}
