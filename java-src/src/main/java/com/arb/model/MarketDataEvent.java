package com.arb.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 标准化行情事件，数据观测层向下游推送的统一格式
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketDataEvent {

    public enum Source {
        POLYMARKET, OKX_OPTION, OKX_SPOT, CHAIN
    }

    private Source        source;
    private Object        data;     // PolyTicker | OkxOptionTicker | OkxSpotTicker | ChainEvent
    private LocalDateTime ts;

    public static MarketDataEvent of(Source source, Object data) {
        return new MarketDataEvent(source, data, LocalDateTime.now());
    }

    public PolyTicker asPolyTicker() {
        return (PolyTicker) data;
    }

    public OkxOptionTicker asOkxOption() {
        return (OkxOptionTicker) data;
    }

    public OkxSpotTicker asOkxSpot() {
        return (OkxSpotTicker) data;
    }
}
