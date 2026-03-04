package com.arb.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Polymarket 实时行情（单个 Outcome 的快照）
 * 每次行情更新写入 MySQL，用于历史回溯与信号分析
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "poly_ticker",
    indexes = {
        @Index(name = "idx_market_outcome_ts", columnList = "market_id, outcome, ts"),
        @Index(name = "idx_ts",                columnList = "ts"),
        @Index(name = "idx_status",            columnList = "status"),
    }
)
public class PolyTicker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Polymarket condition_id（市场唯一标识）*/
    @Column(name = "market_id", nullable = false, length = 100)
    private String marketId;

    /** 事件标题 */
    @Column(name = "event_title", length = 500)
    private String eventTitle;

    /** YES / NO */
    @Column(name = "outcome", nullable = false, length = 10)
    private String outcome;

    /** 买一价 */
    @Column(name = "best_bid", precision = 10, scale = 6)
    private BigDecimal bestBid;

    /** 卖一价 */
    @Column(name = "best_ask", precision = 10, scale = 6)
    private BigDecimal bestAsk;

    /** 最新成交价 */
    @Column(name = "last_price", precision = 10, scale = 6)
    private BigDecimal lastPrice;

    /** 中间价 = (bid + ask) / 2 */
    @Column(name = "mid_price", precision = 10, scale = 6)
    private BigDecimal midPrice;

    /** 价差 = ask - bid */
    @Column(name = "spread", precision = 10, scale = 6)
    private BigDecimal spread;

    /** 24h 成交量（USDC）*/
    @Column(name = "volume_24h", precision = 20, scale = 4)
    private BigDecimal volume24h;

    /** 当前盘口流动性（USDC）*/
    @Column(name = "liquidity", precision = 20, scale = 4)
    private BigDecimal liquidity;

    /** 市场到期时间 */
    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    /** 市场状态：ACTIVE / RESOLVED / PAUSED / CLOSED */
    @Column(name = "status", length = 20)
    private String status;

    /** 行情时间戳 */
    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    // ─── 计算属性 ─────────────────────────────────────────────────

    /** 隐含概率（Poly 价格即概率） */
    @Transient
    public BigDecimal getImpliedProb() {
        return midPrice;
    }

    /** 是否有足够流动性 */
    @Transient
    public boolean isLiquid() {
        if (spread == null || liquidity == null) return false;
        return spread.compareTo(new BigDecimal("0.05")) < 0
            && liquidity.compareTo(new BigDecimal("1000")) > 0;
    }
}
