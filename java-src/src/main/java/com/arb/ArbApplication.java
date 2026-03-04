package com.arb;

import com.arb.event.MarketEventPublisher;
import com.arb.model.MarketDataEvent;
import com.arb.model.OkxSpotTicker;
import com.arb.model.PolyTicker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
public class ArbApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArbApplication.class, args);
    }

    /**
     * 注册示例事件监听器（后续替换为盘口计算引擎）
     */
    @Bean
    CommandLineRunner registerEventHandlers(MarketEventPublisher publisher) {
        return args -> {
            publisher.subscribe(event -> {
                switch (event.getSource()) {
                    case POLYMARKET -> {
                        PolyTicker t = event.asPolyTicker();
                        if (t.isLiquid()) {
                            log.info("[POLY] {:<40} {} | bid={} ask={} prob={:.1%}",
                                truncate(t.getEventTitle(), 40),
                                t.getOutcome(),
                                t.getBestBid(),
                                t.getBestAsk(),
                                t.getMidPrice()
                            );
                        }
                    }
                    case OKX_SPOT -> {
                        OkxSpotTicker s = event.asOkxSpot();
                        log.debug("[SPOT] {}: ${}", s.getSymbol(), s.getPrice());
                    }
                    default -> {}
                }
            });
            log.info("事件监听器注册完成");
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
