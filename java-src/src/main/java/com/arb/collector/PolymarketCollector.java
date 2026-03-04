package com.arb.collector;

import com.arb.cache.MarketDataCache;
import com.arb.config.CollectorProperties;
import com.arb.event.MarketEventPublisher;
import com.arb.model.MarketDataEvent;
import com.arb.model.PolyTicker;
import com.arb.repository.PolyTickerRepository;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polymarket 行情采集器
 *
 * 1. @PostConstruct 启动时：REST 拉取活跃市场列表
 * 2. WebSocket 订阅实时行情（自动重连）
 * 3. @Scheduled 定时刷新市场列表，补订新市场
 * 4. 行情更新 → 写缓存 → 异步落库 → 发布事件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketCollector {

    private static final String REST_URL = "https://clob.polymarket.com";

    private final OkHttpClient            httpClient;
    private final ObjectMapper            objectMapper;
    private final MarketDataCache         cache;
    private final MarketEventPublisher    publisher;
    private final PolyTickerRepository    repository;
    private final CollectorProperties     props;

    private final AtomicBoolean           running  = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();

    /** 已订阅的 token_id 集合（YES/NO 各一个 token） */
    private final Set<String> subscribedTokenIds = ConcurrentHashMap.newKeySet();

    /** token_id → {marketId, outcome} 映射 */
    private final Map<String, String[]> tokenMeta = new ConcurrentHashMap<>();
    // value: [condition_id, outcome, event_title]

    // ─── 生命周期 ─────────────────────────────────────────────────

    @PostConstruct
    @Async("collectorExecutor")
    public void start() {
        running.set(true);
        log.info("Polymarket 采集器启动");
        connectWebSocket();
        refreshMarkets();      // 首次立即拉取 + 订阅 WS
        initPricesFromRest();  // REST 初始化当前价格（WS 只推增量）
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        WebSocket ws = wsRef.get();
        if (ws != null) ws.close(1000, "shutdown");
        log.info("Polymarket 采集器已停止");
    }

    // ─── WebSocket ────────────────────────────────────────────────

    private void connectWebSocket() {
        if (!running.get()) return;

        Request request = new Request.Builder()
            .url(props.getPolymarket().getWsUrl())
            .build();

        httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                wsRef.set(ws);
                log.info("Polymarket WS 已连接");
                // 重连后重新订阅已知 token
                if (!subscribedTokenIds.isEmpty()) {
                    subscribeTokens(ws, new ArrayList<>(subscribedTokenIds));
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                wsRef.set(null);
                log.warn("Polymarket WS 断开: {}, {}ms 后重连", t.getMessage(),
                    props.getPolymarket().getReconnectDelayMs());
                if (running.get()) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                wsRef.set(null);
                log.info("Polymarket WS 关闭: code={} reason={}", code, reason);
                if (running.get()) {
                    scheduleReconnect();
                }
            }
        });
    }

    private void scheduleReconnect() {
        try {
            Thread.sleep(props.getPolymarket().getReconnectDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectWebSocket();
    }

    private void subscribeTokens(WebSocket ws, List<String> tokenIds) {
        if (tokenIds.isEmpty()) return;
        try {
            // Polymarket WS 协议：channel=market, assets_ids=[token_id...]
            // 每次最多 100 个 token，分批订阅
            int batchSize = 100;
            for (int i = 0; i < tokenIds.size(); i += batchSize) {
                List<String> batch = tokenIds.subList(i, Math.min(i + batchSize, tokenIds.size()));
                Map<String, Object> msg = new java.util.LinkedHashMap<>();
                msg.put("type",      "subscribe");
                msg.put("channel",   "market");
                msg.put("assets_ids", batch);
                ws.send(objectMapper.writeValueAsString(msg));
            }
            subscribedTokenIds.addAll(tokenIds);
            log.info("Polymarket WS 订阅 {} 个 token", tokenIds.size());
        } catch (Exception e) {
            log.error("订阅失败: {}", e.getMessage());
        }
    }

    // ─── 消息处理 ─────────────────────────────────────────────────

    private void handleMessage(String raw) {
        // Polymarket WS 有时推送非 JSON 的握手确认（如 "INVALID"），直接忽略
        if (!raw.startsWith("{") && !raw.startsWith("[")) {
            log.debug("Polymarket WS 非 JSON 消息（忽略）: {}", raw.length() > 50 ? raw.substring(0, 50) : raw);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);

            // 有时返回数组
            if (root.isArray()) {
                for (JsonNode node : root) processNode(node);
            } else {
                processNode(root);
            }
        } catch (Exception e) {
            log.error("消息解析异常: {} | raw={}", e.getMessage(), raw.length() > 200 ? raw.substring(0, 200) : raw);
        }
    }

    private void processNode(JsonNode node) {
        String eventType = node.path("event_type").asText(node.path("type").asText(""));
        switch (eventType) {
            case "price_change" -> onPriceChange(node);
            case "book"         -> { /* 订单簿快照，暂跳过 */ }
            default             -> log.debug("未知 Poly 事件: {}", eventType);
        }
    }

    private void onPriceChange(JsonNode msg) {
        // WS 推送: asset_id = token_id，通过 tokenMeta 反查 conditionId + outcome
        String tokenId = msg.path("asset_id").asText("");
        if (tokenId.isEmpty()) return;

        String[] meta      = tokenMeta.get(tokenId);
        String marketId    = meta != null ? meta[0] : tokenId;
        String metaOutcome = meta != null ? meta[1] : "YES";
        String eventTitle  = meta != null ? meta[2] : msg.path("market").asText("");

        JsonNode changes = msg.has("changes") ? msg.get("changes") : objectMapper.createArrayNode().add(msg);

        for (JsonNode change : changes) {
            String side    = change.path("side").asText("");
            BigDecimal price = toBD(change.path("price").asText("0"));

            // 从缓存取已有数据做增量更新
            Optional<PolyTicker> existing = cache.getPolyTicker(marketId, metaOutcome);
            BigDecimal bestBid = existing.map(PolyTicker::getBestBid).orElse(BigDecimal.ZERO);
            BigDecimal bestAsk = existing.map(PolyTicker::getBestAsk).orElse(BigDecimal.ONE);

            if ("BUY".equals(side))  bestBid = price;
            if ("SELL".equals(side)) bestAsk = price;

            BigDecimal mid    = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
            BigDecimal spread = bestAsk.subtract(bestBid);

            PolyTicker ticker = PolyTicker.builder()
                .marketId(marketId)
                .eventTitle(eventTitle)
                .outcome(metaOutcome)
                .bestBid(bestBid)
                .bestAsk(bestAsk)
                .lastPrice(price)
                .midPrice(mid)
                .spread(spread)
                .volume24h(toBD(msg.path("volume").asText("0")))
                .liquidity(toBD(msg.path("liquidity").asText("0")))
                .expireTime(parseDateTime(msg.path("end_date_iso").asText("")))
                .status("ACTIVE")
                .ts(LocalDateTime.now())
                .build();

            // 写缓存
            cache.putPolyTicker(ticker);

            // 异步落库
            saveAsync(ticker);

            // 发布事件
            publisher.publish(MarketDataEvent.of(MarketDataEvent.Source.POLYMARKET, ticker));
        }
    }

    // ─── REST：市场列表刷新 ────────────────────────────────────────

    /** 每 5 分钟刷新一次活跃市场，补订新市场 */
    @Scheduled(fixedRateString = "${collector.polymarket.refresh-interval-seconds:300}000",
               initialDelay = 60000)
    public void refreshMarkets() {
        log.info("刷新 Polymarket 市场列表...");
        try {
            List<String[]> tokens = fetchActiveTokens();  // [token_id, condition_id, outcome, title]
            List<String> newTokenIds = tokens.stream().map(t -> t[0]).toList();
            Set<String> toSubscribe = new HashSet<>(newTokenIds);
            toSubscribe.removeAll(subscribedTokenIds);

            // 更新 token 元数据映射
            for (String[] t : tokens) {
                tokenMeta.put(t[0], new String[]{t[1], t[2], t[3]});
            }

            if (!toSubscribe.isEmpty()) {
                WebSocket ws = wsRef.get();
                if (ws != null) {
                    subscribeTokens(ws, new ArrayList<>(toSubscribe));
                }
            }
            log.info("Polymarket 活跃市场: token总数={}, 新增订阅={}", newTokenIds.size(), toSubscribe.size());
        } catch (Exception e) {
            log.error("市场列表刷新失败: {}", e.getMessage());
        }
    }

    /**
     * 拉取活跃市场的 token 列表
     * @return List of [token_id, condition_id, outcome, event_title]
     */
    private List<String[]> fetchActiveTokens() throws Exception {
        List<String[]> tokens  = new ArrayList<>();
        String nextCursor      = null;
        List<String> keywords  = props.getPolymarket().getKeywords();
        int maxMarkets         = props.getPolymarket().getMaxMarkets();

        do {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(REST_URL + "/markets").newBuilder()
                .addQueryParameter("active", "true")
                .addQueryParameter("closed", "false")
                .addQueryParameter("limit", "100");
            if (nextCursor != null) urlBuilder.addQueryParameter("next_cursor", nextCursor);

            Request req = new Request.Builder().url(urlBuilder.build()).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) break;
                JsonNode data = objectMapper.readTree(resp.body().string());

                for (JsonNode market : data.path("data")) {
                    String title = (market.path("question").asText("") + " " +
                                   market.path("description").asText("")).toLowerCase();
                    boolean match = keywords.stream().anyMatch(title::contains);
                    if (!match) continue;

                    String conditionId = market.path("condition_id").asText("");
                    String eventTitle  = market.path("question").asText("");

                    // 每个 market 有 tokens 数组，提取 token_id
                    for (JsonNode token : market.path("tokens")) {
                        String tokenId = token.path("token_id").asText("");
                        String outcome = token.path("outcome").asText("YES").toUpperCase();
                        if (!tokenId.isEmpty()) {
                            // 统一成 YES/NO 标签
                            String normalizedOutcome = outcome.contains("YES") || outcome.isEmpty() ? "YES" : "NO";
                            // 对于非 YES/NO 命名（如球队名），按顺序标记
                            tokens.add(new String[]{tokenId, conditionId, normalizedOutcome, eventTitle});
                        }
                    }
                }
                nextCursor = data.path("next_cursor").asText(null);
            }
        } while (nextCursor != null && !nextCursor.isEmpty() && tokens.size() / 2 < maxMarkets);

        return tokens;
    }

    // ─── REST 初始化价格 ─────────────────────────────────────────

    /**
     * 启动时从 REST 拉取当前价格初始化缓存
     * WS 只推送增量变动，首次需要 REST 全量快照
     */
    private void initPricesFromRest() {
        log.info("Polymarket REST 初始化价格...");
        int count = 0;
        try {
            String nextCursor = null;
            List<String> keywords = props.getPolymarket().getKeywords();

            do {
                HttpUrl.Builder urlBuilder = HttpUrl.parse(REST_URL + "/markets").newBuilder()
                    .addQueryParameter("active", "true")
                    .addQueryParameter("closed", "false")
                    .addQueryParameter("limit", "100");
                if (nextCursor != null) urlBuilder.addQueryParameter("next_cursor", nextCursor);

                Request req = new Request.Builder().url(urlBuilder.build()).build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) break;
                    JsonNode data = objectMapper.readTree(resp.body().string());

                    for (JsonNode market : data.path("data")) {
                        String title = (market.path("question").asText("") + " "
                            + market.path("description").asText("")).toLowerCase();
                        boolean match = keywords.stream().anyMatch(title::contains);
                        if (!match) continue;

                        String conditionId = market.path("condition_id").asText("");
                        String eventTitle  = market.path("question").asText("");
                        JsonNode tokensNode = market.path("tokens");

                        // tokens 数组里有当前价格
                        for (JsonNode token : tokensNode) {
                            String tokenId  = token.path("token_id").asText("");
                            String outcome  = token.path("outcome").asText("").toUpperCase();
                            BigDecimal price = toBD(token.path("price").asText("0"));

                            String normalOutcome = outcome.contains("YES") || outcome.isEmpty() ? "YES" : "NO";

                            // tokenMeta 同步更新
                            tokenMeta.put(tokenId, new String[]{conditionId, normalOutcome, eventTitle});

                            // 构造初始 ticker（bid=ask=price，spread=0）
                            PolyTicker ticker = PolyTicker.builder()
                                .marketId(conditionId)
                                .eventTitle(eventTitle)
                                .outcome(normalOutcome)
                                .bestBid(price)
                                .bestAsk(price)
                                .lastPrice(price)
                                .midPrice(price)
                                .spread(BigDecimal.ZERO)
                                .volume24h(toBD(market.path("volume").asText("0")))
                                .liquidity(toBD(market.path("liquidity").asText("0")))
                                .expireTime(parseDateTime(market.path("end_date_iso").asText("")))
                                .status("ACTIVE")
                                .ts(LocalDateTime.now())
                                .build();

                            cache.putPolyTicker(ticker);
                            saveAsync(ticker);
                            publisher.publish(MarketDataEvent.of(MarketDataEvent.Source.POLYMARKET, ticker));
                            count++;
                        }
                    }
                    nextCursor = data.path("next_cursor").asText(null);
                }
            } while (nextCursor != null && !nextCursor.isEmpty() && count < props.getPolymarket().getMaxMarkets() * 2);

        } catch (Exception e) {
            log.error("REST 价格初始化失败: {}", e.getMessage());
        }
        log.info("Polymarket REST 初始化完成，写入 {} 条价格", count);
    }

    // ─── 异步落库 ─────────────────────────────────────────────────

    @Async("collectorExecutor")
    public void saveAsync(PolyTicker ticker) {
        try {
            repository.save(ticker);
        } catch (Exception e) {
            log.error("PolyTicker 落库失败: {}", e.getMessage());
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────

    private BigDecimal toBD(String s) {
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s.replace("Z", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 状态查询 ─────────────────────────────────────────────────

    public boolean isConnected() {
        return wsRef.get() != null;
    }

    public int getSubscribedCount() {
        return subscribedTokenIds.size();
    }
}
