package com.arb.manager;

import com.arb.cache.MarketDataCache;
import com.arb.collector.OkxCollector;
import com.arb.collector.PolymarketCollector;
import com.arb.event.MarketEventPublisher;
import com.arb.model.*;
import com.arb.repository.OkxOptionTickerRepository;
import com.arb.repository.PolyTickerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据观测层统一门面
 *
 * 职责：
 *   1. 统一查询接口（供计算引擎调用）
 *   2. 连接状态监控 + 健康检查
 *   3. 历史数据清理
 *   4. 日志统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataManager {

    private final MarketDataCache         cache;
    private final MarketEventPublisher    publisher;
    private final PolymarketCollector     polyCollector;
    private final OkxCollector           okxCollector;
    private final PolyTickerRepository   polyRepo;
    private final OkxOptionTickerRepository optionRepo;

    // ─── 统一查询接口 ─────────────────────────────────────────────

    public Optional<PolyTicker> getPolyTicker(String marketId, String outcome) {
        return cache.getPolyTicker(marketId, outcome);
    }

    /** 同时返回 YES + NO 行情 */
    public Map<String, PolyTicker> getPolyPair(String marketId) {
        Map<String, PolyTicker> pair = new LinkedHashMap<>();
        cache.getPolyTicker(marketId, "YES").ifPresent(t -> pair.put("YES", t));
        cache.getPolyTicker(marketId, "NO").ifPresent(t -> pair.put("NO", t));
        return pair;
    }

    public Set<String> getActiveMarketIds() {
        return cache.getActiveMarketIds();
    }

    public List<PolyTicker> getAllPolyTickers() {
        return cache.getAllPolyTickers();
    }

    public Optional<OkxOptionTicker> getOkxOption(String instrumentId) {
        return cache.getOkxOption(instrumentId);
    }

    public List<OkxOptionTicker> getOptionsByUnderlying(String underlying) {
        return cache.getOptionsByUnderlying(underlying);
    }

    public Optional<BigDecimal> getSpotPrice(String underlying) {
        return cache.getSpotPrice(underlying);
    }

    /**
     * 为 Polymarket 市场找最匹配的 OKX Call 期权
     *
     * @param underlying     BTC / ETH
     * @param targetStrike   事件触发价格
     * @param targetDteDays  距 Poly 市场到期天数
     */
    public Optional<OkxOptionTicker> findMatchingOption(
        String underlying, BigDecimal targetStrike, long targetDteDays
    ) {
        return cache.findMatchingCall(
            underlying, targetStrike, targetDteDays,
            0.05,   // strike 偏差 5%
            7       // DTE 偏差 7 天
        );
    }

    // ─── 健康监控 ─────────────────────────────────────────────────

    @Scheduled(fixedRate = 60_000)
    public void logHealth() {
        Map<String, Object> h = health();
        log.info("DataManager 健康: {}", h);

        // 告警：连接断开
        Boolean polyWs = (Boolean) ((Map<?, ?>) h.get("connections")).get("poly_ws");
        Boolean okxWs  = (Boolean) ((Map<?, ?>) h.get("connections")).get("okx_ws");
        if (Boolean.FALSE.equals(polyWs)) log.warn("⚠️  Polymarket WS 未连接！");
        if (Boolean.FALSE.equals(okxWs))  log.warn("⚠️  OKX WS 未连接！");

        // 告警：过期行情
        List<?> stale = (List<?>) h.get("stale_feeds");
        if (!stale.isEmpty()) log.warn("⚠️  过期行情: {}", stale);
    }

    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("cache",       cache.stats());
        h.put("stale_feeds", cache.getStaleKeys(30));
        h.put("connections", Map.of(
            "poly_ws",  polyCollector.isConnected(),
            "okx_ws",   okxCollector.isConnected()
        ));
        h.put("subscribers", publisher.subscriberCount());
        return h;
    }

    // ─── 历史数据清理 ─────────────────────────────────────────────

    /** 每天凌晨 3 点清理 7 天前的历史行情 */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        log.info("清理 {} 之前的历史行情...", cutoff);
        try {
            polyRepo.deleteByTsBefore(cutoff);
            optionRepo.deleteByTsBefore(cutoff);
            log.info("历史行情清理完成");
        } catch (Exception e) {
            log.error("历史行情清理失败: {}", e.getMessage());
        }
    }
}
