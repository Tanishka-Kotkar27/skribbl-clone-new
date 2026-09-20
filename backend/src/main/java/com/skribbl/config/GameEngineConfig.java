package com.skribbl.config;

import com.skribbl.game.DrawingService;
import com.skribbl.game.EngineTimings;
import com.skribbl.game.GameEngine;
import com.skribbl.service.DbGameRecorder;
import com.skribbl.service.WordService;
import com.skribbl.ws.StompGameEvents;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Builds the {@link GameEngine} from its Spring-managed collaborators.
 *
 * <p>The engine is a plain class, not a {@code @Component}, so it stays free of
 * Spring and can be constructed directly in tests with fakes. This is the only
 * place that knows how to assemble the real one.
 *
 * <p>The scheduler is deliberately <em>not</em> exposed as a bean: with
 * {@code @EnableScheduling} on, Spring would pick up a lone
 * {@code ScheduledExecutorService} bean and start running every {@code @Scheduled}
 * method on the game's timer threads.
 */
@Configuration
public class GameEngineConfig {

    /**
     * Timer threads for every room. Two is plenty: each callback holds a room
     * lock for microseconds. Cancelled tasks are removed from the queue
     * immediately so thousands of cancelled hint timers cannot pile up.
     */
    private final ScheduledThreadPoolExecutor scheduler = createScheduler();

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "game-timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean
    public GameEngine gameEngine(StompGameEvents events,
                                 WordService wordService,
                                 DbGameRecorder recorder) {
        return new GameEngine(events, wordService, recorder, scheduler, EngineTimings.defaults());
    }

    /** Stroke relay. Plain class for the same reason as the engine: testable without Spring. */
    @Bean
    public DrawingService drawingService(StompGameEvents events) {
        return new DrawingService(events);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
