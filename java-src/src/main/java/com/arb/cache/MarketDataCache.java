package com.arb.cache;

import com.arb.model.OkxOptionTicker;
import com.arb.model.OkxSpotTicker;
import com.arb.model.PolyTicker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内行情快照缓存（ConcurrentHashMap）
 *
 * 零延迟读取，作为 MySQL 持久化的前端热缓存。
 * 每次行情更新先写缓存，异步落库，计算引擎从缓存读取。
 */
@Slf4j
@Component
public class MarketDataCache {

    // key: "marketId:outcome"
    private final Map<String, PolyTicker>      polyTickers  = new ConcurrentHashMap<>();
    // key: instrumentId
    private final Map<String, OkxOptionTicker> okxOptions   = new ConcurrentHashMap<>();
    // key: symbol (e.g. BTC-USDT)
    private final Map<String, OkxSpotTicker>   okxSpots     = new ConcurrentHashMap<>();

    // ─── Polymarket ───────────────────────────────────────────────

    public void putPolyTicker(PolyTicker ticker) {
        String key = cacheKey(ticker.getMarketId(), ticker.getOutcome());
        polyTickers.put(key, ticker);
    }

    public Optional<PolyTicker> getPolyTicker(String marketId, String outcome) {
        return Optional.ofNullable(polyTickers.get(cacheKey(marketId, outcome)));
    }

    public List<PolyTicker> getAllPolyTickers() {
        return new ArrayList<>(polyTickers.values());
    }

    /** 返回所有有行情的 marketId（去重）*/
    public Set<String> getActiveMarketIds() {
        return polyTickers.keySet().stream()
            .map(k -> k.split(":")[0])
            .collect(Collectors.toSet());
    }

    // ─── OKX 期权 ─────────────────────────────────────────────────

    public void putOkxOption(OkxOptionTicker ticker) {
        okxOptions.put(ticker.getInstrumentId(), ticker);
    }

    public Optional<OkxOptionTicker> getOkxOption(String instrumentId) {
        return Optional.ofNullable(okxOptions.get(instrumentId));
    }

    public List<OkxOptionTicker> getOptionsByUnderlying(String underlying) {
        return okxOptions.values().stream()
            .filter(t -> underlying.equals(t.getUnderlying()))
            .collect(Collectors.toList());
    }

    // ─── OKX 现货 ─────────────────────────────────────────────────

    public void putOkxSpot(OkxSpotTicker ticker) {
        okxSpots.put(ticker.getSymbol(), ticker);
    }

    public Optional<OkxSpotTicker> getOkxSpot(String symbol) {
        return Optional.ofNullable(okxSpots.get(symbol));
    }

    public Optional<BigDecimal> getSpotPrice(String underlying) {
        return getOkxSpot(underlying + "-USDT")
            .map(OkxSpotTicker::getPrice);
    }

    // ─── 最优匹配期权 ─────────────────────────────────────────────

    /**
     * 为 Polymarket 市场找最匹配的 OKX Call 期权
     *
     * @param underlying       标的（BTC / ETH）
     * @param targetStrike     目标行权价
     * @param targetDteDays    目标到期天数
     * @param maxStrikeDiffPct Strike 最大偏差比例（如 0.05 = 5%）
     * @param maxDteDiff       DTE 最大偏差天数
     */
    public Optional<OkxOptionTicker> findMatchingCall(
        String underlying,
        BigDecimal targetStrike,
        long targetDteDays,
        double maxStrikeDiffPct,
        long maxDteDiff
    ) {
        return okxOptions.values().stream()
            .filter(t -> underlying.equals(t.getUnderlying()))
            .filter(t -> "C".equals(t.getOptionType()))
            .filter(t -> {
                if (t.getStrike() == null || targetStrike == null) return false;
                double diff = Math.abs(t.getStrike().subtract(targetStrike)
                    .divide(targetStrike, 6, java.math.RoundingMode.HALF_UP)
                    .doubleValue());
                return diff <= maxStrikeDiffPct;
            })
            .filter(t -> Math.abs(t.getDte() - targetDteDays) <= maxDteDiff)
            .filter(OkxOptionTicker::isLiquid)
            .min(Comparator.comparingDouble(t -> {
                // 综合评分：strike 偏差 + DTE 偏差 + 价差（流动性）
                double strikeDiff = t.getStrike() == null ? 999 :
                    Math.abs(t.getStrike().subtract(targetStrike)
                        .divide(targetStrike, 6, java.math.RoundingMode.HALF_UP).doubleValue());
                double dteDiff   = Math.abs(t.getDte() - targetDteDays) * 0.01;
                double spread    = (t.getBestAsk() != null && t.getBestBid() != null)
                    ? t.getBestAsk().subtract(t.getBestBid()).doubleValue() : 999;
                return strikeDiff * 10 + dteDiff + spread;
            }));
    }

    // ─── 健康检查 ─────────────────────────────────────────────────

    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("polyTickers",  polyTickers.size());
        s.put("okxOptions",   okxOptions.size());
        s.put("okxSpots",     okxSpots.size());
        s.put("polyMarkets",  getActiveMarketIds().size());
        return s;
    }

    /** 返回超过 maxAgeSeconds 未更新的行情 key */
    public List<String> getStaleKeys(int maxAgeSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(maxAgeSeconds);
        List<String> stale = new ArrayList<>();

        polyTickers.forEach((k, t) -> {
            if (t.getTs() != null && t.getTs().isBefore(threshold))
                stale.add("poly:" + k);
        });
        okxOptions.forEach((k, t) -> {
            if (t.getTs() != null && t.getTs().isBefore(threshold))
                stale.add("okx:" + k);
        });
        return stale;
    }

    private String cacheKey(String marketId, String outcome) {
        return marketId + ":" + outcome.toUpperCase();
    }
}
