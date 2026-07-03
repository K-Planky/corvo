package dev.kplanky.othello.config;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@code @Async} and provides the bounded worker pool that computes vs-AI replies off the
 * request thread (spec §9, M8). The pool is deliberately small with a bounded queue: a Hard search is
 * CPU-bound, so unbounded concurrency would thrash, and a bounded queue applies back-pressure rather
 * than letting work pile up without limit.
 *
 * <p>Also turns on {@code @Scheduled} ({@link EnableScheduling}) for the PvP turn-clock sweep (spec
 * §15, M10) and binds its {@code pvp.clock.*} configuration ({@link PvpClockProperties}).
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(PvpClockProperties.class)
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** The executor named by {@code @Async("botExecutor")} on the AI reply worker. */
    @Bean(name = "botExecutor")
    public Executor botExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bot-reply-");
        // The dispatch happens after the human's move already committed, so rejection must NOT throw
        // (that would surface as a 500 on a successful move) and must NOT run on the caller (it's the
        // after-commit request thread — a multi-second search would block it). Under extreme
        // saturation we log and drop: the human's move stands; only the bot's reply is lost.
        executor.setRejectedExecutionHandler((task, exec) ->
                log.error("botExecutor saturated (active={}, queue={}); dropping a vs-AI reply task",
                        exec.getActiveCount(), exec.getQueue().size()));
        executor.initialize();
        return executor;
    }

    /** Async AI-reply failures have no caller to propagate to; log them rather than swallow silently. */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
