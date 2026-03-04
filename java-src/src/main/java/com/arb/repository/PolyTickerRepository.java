package com.arb.repository;

import com.arb.model.PolyTicker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PolyTickerRepository extends JpaRepository<PolyTicker, Long> {

    /** 查最新一条行情 */
    Optional<PolyTicker> findTopByMarketIdAndOutcomeOrderByTsDesc(
        String marketId, String outcome
    );

    /** 查指定时间范围内某市场的行情历史 */
    List<PolyTicker> findByMarketIdAndOutcomeAndTsBetweenOrderByTsAsc(
        String marketId, String outcome,
        LocalDateTime from, LocalDateTime to
    );

    /** 查所有活跃市场的最新行情（用于信号扫描）*/
    @Query("""
        SELECT t FROM PolyTicker t
        WHERE t.ts = (
            SELECT MAX(t2.ts) FROM PolyTicker t2
            WHERE t2.marketId = t.marketId AND t2.outcome = t.outcome
        )
        AND t.status = 'ACTIVE'
        """)
    List<PolyTicker> findLatestActiveAll();

    /** 查活跃且有流动性的市场 */
    @Query("""
        SELECT t FROM PolyTicker t
        WHERE t.status = 'ACTIVE'
          AND t.spread < 0.05
          AND t.liquidity > 1000
          AND t.ts >= :since
        ORDER BY t.ts DESC
        """)
    List<PolyTicker> findLiquidMarkets(@Param("since") LocalDateTime since);

    /** 按 market_id 查所有 Outcome 的最新行情 */
    @Query("""
        SELECT t FROM PolyTicker t
        WHERE t.marketId = :marketId
          AND t.ts = (
              SELECT MAX(t2.ts) FROM PolyTicker t2
              WHERE t2.marketId = t.marketId AND t2.outcome = t.outcome
          )
        """)
    List<PolyTicker> findLatestByMarketId(@Param("marketId") String marketId);

    /** 清理 N 天前的历史数据 */
    void deleteByTsBefore(LocalDateTime cutoff);
}
