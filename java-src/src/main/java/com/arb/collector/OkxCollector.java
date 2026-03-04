package com.arb.collector;

import com.arb.cache.MarketDataCache;
import com.arb.config.CollectorProperties;
import com.arb.event.MarketEventPublisher;
import com.arb.model.MarketDataEvent;
import com.arb.model.OkxOptionTicker;
import com.arb.model.OkxSpotTicker;
import com.arb.repository.OkxOptionTickerRepository;
import com.arb.repository.OkxSpotTickerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OKX 期权行情采集器
 *
 * - WebSocket 订阅 opt-summary（含 Greeks + IV）
 * - WebSocket 订阅 tickers（现货价格）
 * - @Scheduled 定时 REST 拉取 Greeks 补充
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxCollector {

    private final OkHttpClient               httpClient;
    private final ObjectMapper               objectMapper;
    private final MarketDataCache            cache;
    private final MarketEventPublisher       publisher;
    private final OkxOptionTickerRepository  optionRepo;
    private final OkxSpotTickerRepository    spotRepo;
    private final CollectorProperties        props;

    private final AtomicBoolean           running = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();

    // ─── 生命周期 ─────────────────────────────────────────────────

    @PostConstruct
    @Async("collectorExecutor")
    public void start() {
        running.set(true);
        log.info("OKX 采集器启动");
        connectWebSocket();
        // 启动后立刻 REST 拉取一次，不等 initialDelay
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        pollGreeks();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        WebSocket ws = wsRef.get();
        if (ws != null) ws.close(1000, "shutdown");
        log.info("OKX 采集器已停止");
    }

    // ─── WebSocket ────────────────────────────────────────────────

    private void connectWebSocket() {
        if (!running.get()) return;

        Request request = new Request.Builder()
            .url(props.getOkx().getWsUrl())
            .build();

        httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                wsRef.set(ws);
                log.info("OKX WS 已连接");
                subscribeAll(ws);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                wsRef.set(null);
                log.warn("OKX WS 断开: {}", t.getMessage());
                if (running.get()) scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                wsRef.set(null);
                if (running.get()) scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        try {
            Thread.sleep(props.getOkx().getReconnectDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectWebSocket();
    }

    private void subscribeAll(WebSocket ws) {
        List<Map<String, String>> args = new ArrayList<>();

        // 现货行情
        for (String udl : props.getOkx().getUnderlyingList()) {
            args.add(Map.of("channel", "tickers", "instId", udl + "-USDT"));
        }

        // 期权摘要（含 Greeks）- 按 instFamily 订阅（uly 已废弃）
        for (String udl : props.getOkx().getUnderlyingList()) {
            args.add(Map.of("channel", "opt-summary", "instFamily", udl + "-USD"));
        }

        try {
            Map<String, Object> msg = Map.of("op", "subscribe", "args", args);
            ws.send(objectMapper.writeValueAsString(msg));
            log.info("OKX WS 订阅: 现货={} + 期权摘要", props.getOkx().getUnderlyingList());
        } catch (Exception e) {
            log.error("OKX WS 订阅失败: {}", e.getMessage());
        }
    }

    // ─── 消息处理 ─────────────────────────────────────────────────

    private void handleMessage(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);

            // 订阅确认/错误
            if (root.has("event")) {
                if ("error".equals(root.path("event").asText())) {
                    log.error("OKX WS 订阅错误: {}", root.path("msg").asText());
                }
                return;
            }

            String channel = root.path("arg").path("channel").asText("");
            JsonNode data  = root.path("data");

            if (!data.isArray()) return;

            switch (channel) {
                case "opt-summary" -> data.forEach(this::onOptSummary);
                case "tickers"     -> data.forEach(this::onSpotTicker);
                default            -> log.debug("未知 OKX channel: {}", channel);
            }
        } catch (Exception e) {
            log.error("OKX 消息解析异常: {}", e.getMessage());
        }
    }

    private void onOptSummary(JsonNode item) {
        String iid = item.path("instId").asText("");
        if (iid.isEmpty()) return;

        // 解析合约信息: BTC-20260328-100000-C
        String[] parts = iid.split("-");
        if (parts.length < 5) return;

        String underlying = parts[0];
        String expiryStr  = parts[2];     // YYYYMMDD
        BigDecimal strike = toBD(parts[3]);
        String optType    = parts[4];     // C or P

        LocalDate expiry;
        try {
            // OKX 日期格式：260327 = YYMMDD，需补全为 20YYMMDD
            String fullDateStr = expiryStr.length() == 6 ? "20" + expiryStr : expiryStr;
            expiry = LocalDate.parse(fullDateStr, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            log.warn("日期解析失败: instId={} expiryStr={}", iid, expiryStr);
            return;
        }

        // 现货价格
        BigDecimal spotPrice = cache.getSpotPrice(underlying)
            .orElse(toBD(item.path("fwdPx").asText("0")));

        // opt-summary 没有 bid/ask price，用 markVol 作为 mid（IV 曲面价格）
        // bid/ask 的 IV 是 bidVol/askVol
        BigDecimal bidVol = toBD(item.path("bidVol").asText("0"));
        BigDecimal askVol = toBD(item.path("askVol").asText("0"));
        BigDecimal markVol = toBD(item.path("markVol").asText("0"));

        // opt-summary 返回的价格实际上是 IV（波动率），不是期权价格
        // bid/ask 用 bidVol/askVol 近似表示
        BigDecimal bid = bidVol;
        BigDecimal ask = askVol;
        BigDecimal mid = (bid.add(ask)).compareTo(BigDecimal.ZERO) > 0
            ? bid.add(ask).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP)
            : markVol;

        // IV 直接用 markVol（已经是小数形式，如 0.65 = 65%）
        BigDecimal iv = markVol;

        OkxOptionTicker ticker = OkxOptionTicker.builder()
            .instrumentId(iid)
            .underlying(underlying)
            .strike(strike)
            .expiry(expiry)
            .optionType(optType)
            .bestBid(bid)
            .bestAsk(ask)
            .lastPrice(toBD(item.path("last").asText("0")))
            .midPrice(mid)
            .iv(iv)
            .markPrice(toBD(item.path("markVol").asText("0")))
            .delta(toBD(item.path("delta").asText("0")))
            .gamma(toBD(item.path("gamma").asText("0")))
            .vega(toBD(item.path("vega").asText("0")))
            .theta(toBD(item.path("theta").asText("0")))
            .spotPrice(spotPrice)
            .openInterest(toBD(item.path("oi").asText("0")))
            .volume24h(toBD(item.path("volCcy24h").asText("0")))
            .ts(LocalDateTime.now())
            .build();

        // 只追踪 max_dte 内的
        if (ticker.getDte() > props.getOkx().getMaxDte() || ticker.getDte() < 0) return;

        cache.putOkxOption(ticker);
        saveAsync(ticker);
        publisher.publish(MarketDataEvent.of(MarketDataEvent.Source.OKX_OPTION, ticker));
    }

    private void onSpotTicker(JsonNode item) {
        String symbol = item.path("instId").asText("");
        if (symbol.isEmpty()) return;

        OkxSpotTicker ticker = OkxSpotTicker.builder()
            .symbol(symbol)
            .price(toBD(item.path("last").asText("0")))
            .change24h(toBD(item.path("change24h").asText("0")))
            .volume24h(toBD(item.path("vol24h").asText("0")))
            .ts(LocalDateTime.now())
            .build();

        cache.putOkxSpot(ticker);
        saveAsync(ticker);
        publisher.publish(MarketDataEvent.of(MarketDataEvent.Source.OKX_SPOT, ticker));
    }

    // ─── REST Greeks 补充轮询 ──────────────────────────────────────

    /** 每 10s REST 补充一次 Greeks，防止 WS 推送不及时 */
    @Scheduled(fixedRateString = "${collector.okx.greeks-poll-interval-seconds:10}000",
               initialDelay = 30000)
    public void pollGreeks() {
        for (String udl : props.getOkx().getUnderlyingList()) {
            try {
                pollGreeksByUnderlying(udl);
            } catch (Exception e) {
                log.error("OKX Greeks 轮询失败 {}: {}", udl, e.getMessage());
            }
        }
    }

    private void pollGreeksByUnderlying(String underlying) throws Exception {
        HttpUrl url = HttpUrl.parse(props.getOkx().getRestUrl() + "/public/opt-summary")
            .newBuilder()
            .addQueryParameter("instFamily", underlying + "-USD")
            .build();

        Request req = new Request.Builder().url(url).build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return;
            JsonNode data = objectMapper.readTree(resp.body().string()).path("data");
            if (data.isArray()) {
                data.forEach(this::onOptSummary);
            }
        }
    }

    // ─── 异步落库 ─────────────────────────────────────────────────

    @Async("collectorExecutor")
    public void saveAsync(OkxOptionTicker ticker) {
        try {
            optionRepo.save(ticker);
        } catch (Exception e) {
            log.error("OkxOptionTicker 落库失败: {}", e.getMessage());
        }
    }

    @Async("collectorExecutor")
    public void saveAsync(OkxSpotTicker ticker) {
        try {
            spotRepo.save(ticker);
        } catch (Exception e) {
            log.error("OkxSpotTicker 落库失败: {}", e.getMessage());
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────

    private BigDecimal toBD(String s) {
        try {
            BigDecimal v = new BigDecimal(s);
            return v;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    public boolean isConnected() { return wsRef.get() != null; }
}
