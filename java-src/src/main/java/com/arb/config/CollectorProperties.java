package com.arb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 采集器配置，绑定 application.yml 中的 collector.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {

    private Polymarket polymarket = new Polymarket();
    private Okx okx = new Okx();
    private Chain chain = new Chain();

    @Data
    public static class Polymarket {
        private String wsUrl   = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
        private String restUrl = "https://clob.polymarket.com";
        private int maxMarkets = 50;
        private String keywordFilter = "bitcoin,btc,ethereum,eth,price,above,below";
        private long refreshIntervalSeconds = 300;
        private long reconnectDelayMs = 3000;

        public List<String> getKeywords() {
            return List.of(keywordFilter.toLowerCase().split(","));
        }
    }

    @Data
    public static class Okx {
        private String wsUrl   = "wss://ws.okx.com:8443/ws/v5/public";
        private String restUrl = "https://www.okx.com/api/v5";
        private String underlyings = "BTC,ETH";
        private int maxDte = 60;
        private long greeksPollIntervalSeconds = 10;
        private long reconnectDelayMs = 3000;

        public List<String> getUnderlyingList() {
            return List.of(underlyings.split(","));
        }
    }

    @Data
    public static class Chain {
        private String polygonWsUrl  = "";
        private String polygonRpcUrl = "";
        private boolean enabled = false;
    }
}
