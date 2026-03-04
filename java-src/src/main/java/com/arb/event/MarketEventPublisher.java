package com.arb.event;

import com.arb.model.MarketDataEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 行情事件总线（发布-订阅）
 *
 * 采集器 → publish() → 所有注册的 subscriber
 *
 * 用法：
 *   publisher.subscribe(event -> { ... });
 *   publisher.publish(MarketDataEvent.of(...));
 */
@Slf4j
@Component
public class MarketEventPublisher {

    private final List<Consumer<MarketDataEvent>> subscribers = new CopyOnWriteArrayList<>();

    /** 注册下游监听器（线程安全，支持运行时动态注册）*/
    public void subscribe(Consumer<MarketDataEvent> handler) {
        subscribers.add(handler);
        log.info("MarketEventPublisher: 新订阅者注册，当前订阅数={}", subscribers.size());
    }

    /** 发布行情事件到所有订阅者 */
    public void publish(MarketDataEvent event) {
        for (Consumer<MarketDataEvent> handler : subscribers) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                log.error("订阅者处理异常: source={} error={}", event.getSource(), e.getMessage(), e);
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
