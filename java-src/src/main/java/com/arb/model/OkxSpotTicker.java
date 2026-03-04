package com.arb.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OKX 现货实时价格
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "okx_spot_ticker",
    indexes = {
        @Index(name = "idx_symbol_ts", columnList = "symbol, ts"),
    }
)
public class OkxSpotTicker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. BTC-USDT */
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "price", precision = 20, scale = 4)
    private BigDecimal price;

    @Column(name = "change_24h", precision = 10, scale = 6)
    private BigDecimal change24h;

    @Column(name = "volume_24h", precision = 20, scale = 4)
    private BigDecimal volume24h;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;
}
