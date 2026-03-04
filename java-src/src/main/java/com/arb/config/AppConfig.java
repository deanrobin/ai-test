package com.arb.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
@EnableAsync
public class AppConfig {

    /**
     * 共享 OkHttpClient（WebSocket + REST 复用）
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS)
            .writeTimeout(10,   TimeUnit.SECONDS)
            .pingInterval(20,   TimeUnit.SECONDS)   // WebSocket keepalive
            .retryOnConnectionFailure(true)
            .build();
    }

    /**
     * Jackson，支持 Java 8 时间类型
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 异步任务线程池（采集器异步持久化用）
     */
    @Bean(name = "collectorExecutor")
    public Executor collectorExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(1000);
        exec.setThreadNamePrefix("collector-");
        exec.initialize();
        return exec;
    }
}
