package com.arb.cache;

import com.arb.model.OkxOptionTicker;
import com.arb.model.PolyTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataCacheTest {

    private MarketDataCache cache;

    @BeforeEach
    void setUp() {
        cache = new MarketDataCache();
    }

    @Test
    void putAndGetPolyTicker() {
        PolyTicker ticker = PolyTicker.builder()
            .marketId("market-001")
            .outcome("YES")
            .bestBid(new BigDecimal("0.62"))
            .bestAsk(new BigDecimal("0.64"))
            .midPrice(new BigDecimal("0.63"))
            .spread(new BigDecimal("0.02"))
            .liquidity(new BigDecimal("5000"))
            .status("ACTIVE")
            .ts(LocalDateTime.now())
            .build();

        cache.putPolyTicker(ticker);

        Optional<PolyTicker> found = cache.getPolyTicker("market-001", "YES");
        assertThat(found).isPresent();
        assertThat(found.get().getMidPrice()).isEqualByComparingTo("0.63");
    }

    @Test
    void isLiquid_returnsTrueWhenSpreadSmallAndLiquidityHigh() {
        PolyTicker ticker = PolyTicker.builder()
            .spread(new BigDecimal("0.02"))
            .liquidity(new BigDecimal("5000"))
            .ts(LocalDateTime.now())
            .build();

        assertThat(ticker.isLiquid()).isTrue();
    }

    @Test
    void isLiquid_returnsFalseWhenSpreadTooLarge() {
        PolyTicker ticker = PolyTicker.builder()
            .spread(new BigDecimal("0.10"))
            .liquidity(new BigDecimal("5000"))
            .ts(LocalDateTime.now())
            .build();

        assertThat(ticker.isLiquid()).isFalse();
    }

    @Test
    void findMatchingCall_returnsClosestStrike() {
        // 放入几个期权
        for (int strike : new int[]{90000, 95000, 100000, 105000}) {
            OkxOptionTicker opt = OkxOptionTicker.builder()
                .instrumentId("BTC-20261231-" + strike + "-C")
                .underlying("BTC")
                .strike(new BigDecimal(strike))
                .expiry(LocalDate.of(2026, 12, 31))
                .optionType("C")
                .bestBid(new BigDecimal("0.01"))
                .bestAsk(new BigDecimal("0.02"))
                .ts(LocalDateTime.now())
                .build();
            cache.putOkxOption(opt);
        }

        Optional<OkxOptionTicker> match = cache.findMatchingCall(
            "BTC",
            new BigDecimal("98000"),  // 目标 strike
            90L,                       // 目标 DTE
            0.10,                      // 10% 偏差
            30L                        // DTE 偏差 30 天
        );

        assertThat(match).isPresent();
        // 最近的 strike 是 100000（偏差 2.04%）
        assertThat(match.get().getStrike()).isEqualByComparingTo("100000");
    }

    @Test
    void getStaleKeys_detectsOldTickers() throws InterruptedException {
        PolyTicker old = PolyTicker.builder()
            .marketId("old-market")
            .outcome("YES")
            .ts(LocalDateTime.now().minusSeconds(60))
            .build();
        cache.putPolyTicker(old);

        PolyTicker fresh = PolyTicker.builder()
            .marketId("new-market")
            .outcome("YES")
            .ts(LocalDateTime.now())
            .build();
        cache.putPolyTicker(fresh);

        var stale = cache.getStaleKeys(30);
        assertThat(stale).anyMatch(k -> k.contains("old-market"));
        assertThat(stale).noneMatch(k -> k.contains("new-market"));
    }
}
