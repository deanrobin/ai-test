package com.arb.repository;

import com.arb.model.OkxOptionTicker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OkxOptionTickerRepository extends JpaRepository<OkxOptionTicker, Long> {

    Optional<OkxOptionTicker> findTopByInstrumentIdOrderByTsDesc(String instrumentId);

    /** 查某标的 + 到期日的完整期权链（最新行情）*/
    @Query("""
        SELECT t FROM OkxOptionTicker t
        WHERE t.underlying = :underlying
          AND t.expiry = :expiry
          AND t.ts = (
              SELECT MAX(t2.ts) FROM OkxOptionTicker t2
              WHERE t2.instrumentId = t.instrumentId
          )
        ORDER BY t.strike ASC
        """)
    List<OkxOptionTicker> findOptionChain(
        @Param("underlying") String underlying,
        @Param("expiry") LocalDate expiry
    );

    /** 查所有可用到期日 */
    @Query("SELECT DISTINCT t.expiry FROM OkxOptionTicker t WHERE t.underlying = :underlying AND t.expiry >= CURRENT_DATE ORDER BY t.expiry")
    List<LocalDate> findAvailableExpiries(@Param("underlying") String underlying);

    /** 找最匹配 Poly 市场的期权合约 */
    @Query("""
        SELECT t FROM OkxOptionTicker t
        WHERE t.underlying = :underlying
          AND t.optionType = :optionType
          AND ABS(t.strike - :targetStrike) / :targetStrike < :strikeDiffPct
          AND t.expiry BETWEEN :expiryFrom AND :expiryTo
          AND t.ts >= :since
        ORDER BY ABS(t.strike - :targetStrike) ASC, (t.bestAsk - t.bestBid) ASC
        """)
    List<OkxOptionTicker> findMatchingOptions(
        @Param("underlying")     String underlying,
        @Param("optionType")     String optionType,
        @Param("targetStrike")   java.math.BigDecimal targetStrike,
        @Param("strikeDiffPct")  double strikeDiffPct,
        @Param("expiryFrom")     LocalDate expiryFrom,
        @Param("expiryTo")       LocalDate expiryTo,
        @Param("since")          LocalDateTime since
    );

    void deleteByTsBefore(LocalDateTime cutoff);
}
