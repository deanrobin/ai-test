package com.arb.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * OKX 期权合约实时行情（含 Greeks）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "okx_option_ticker",
    indexes = {
        @Index(name = "idx_instrument_ts",    columnList = "instrument_id, ts"),
        @Index(name = "idx_underlying_expiry", columnList = "underlying, expiry, strike"),
        @Index(name = "idx_ts",               columnList = "ts"),
    }
)
public class OkxOptionTicker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. BTC-20260328-100000-C */
    @Column(name = "instrument_id", nullable = false, length = 60)
    private String instrumentId;

    /** BTC / ETH / SOL */
    @Column(name = "underlying", nullable = false, length = 20)
    private String underlying;

    /** 行权价 */
    @Column(name = "strike", precision = 20, scale = 2)
    private BigDecimal strike;

    /** 到期日 */
    @Column(name = "expiry")
    private LocalDate expiry;

    /** C = Call / P = Put */
    @Column(name = "option_type", length = 1)
    private String optionType;

    @Column(name = "best_bid", precision = 15, scale = 8)
    private BigDecimal bestBid;

    @Column(name = "best_ask", precision = 15, scale = 8)
    private BigDecimal bestAsk;

    @Column(name = "last_price", precision = 15, scale = 8)
    private BigDecimal lastPrice;

    @Column(name = "mid_price", precision = 15, scale = 8)
    private BigDecimal midPrice;

    /** 隐含波动率（小数，如 0.65 = 65%）*/
    @Column(name = "iv", precision = 10, scale = 6)
    private BigDecimal iv;

    /** 标记价格 */
    @Column(name = "mark_price", precision = 15, scale = 8)
    private BigDecimal markPrice;

    // ─── Greeks ───────────────────────────────────────────────────

    @Column(name = "delta", precision = 10, scale = 6)
    private BigDecimal delta;

    @Column(name = "gamma", precision = 15, scale = 10)
    private BigDecimal gamma;

    @Column(name = "vega", precision = 10, scale = 6)
    private BigDecimal vega;

    @Column(name = "theta", precision = 10, scale = 6)
    private BigDecimal theta;

    // ─────────────────────────────────────────────────────────────

    /** 标的现货价 */
    @Column(name = "spot_price", precision = 20, scale = 4)
    private BigDecimal spotPrice;

    /** 未平仓量（张）*/
    @Column(name = "open_interest", precision = 20, scale = 4)
    private BigDecimal openInterest;

    @Column(name = "volume_24h", precision = 20, scale = 4)
    private BigDecimal volume24h;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    // ─── 计算属性 ─────────────────────────────────────────────────

    /** 距到期天数 */
    @Transient
    public long getDte() {
        if (expiry == null) return 0;
        return ChronoUnit.DAYS.between(LocalDate.now(), expiry);
    }

    /** 距到期年化时间 */
    @Transient
    public double getTtm() {
        return getDte() / 365.0;
    }

    /** 是否有流动性 */
    @Transient
    public boolean isLiquid() {
        return bestBid != null && bestAsk != null
            && bestBid.compareTo(BigDecimal.ZERO) > 0
            && bestAsk.compareTo(BigDecimal.ZERO) > 0;
    }
}
