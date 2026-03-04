package com.arb.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Polygon 链上事件记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "chain_event",
    indexes = {
        @Index(name = "idx_market_id",   columnList = "market_id"),
        @Index(name = "idx_event_type",  columnList = "event_type"),
        @Index(name = "idx_ts",          columnList = "ts"),
    }
)
public class ChainEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MARKET_CREATED / CONDITION_RESOLVED / REDEMPTION / DISPUTE */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "market_id", length = 100)
    private String marketId;

    @Column(name = "tx_hash", length = 100)
    private String txHash;

    @Column(name = "block_number")
    private Long blockNumber;

    /** 事件原始 payload（JSON 序列化）*/
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;
}
