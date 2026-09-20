package com.skribbl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the skribbl.io clone backend.
 *
 * <p>Scheduling is enabled here because the game engine (Phase 3) drives round
 * timers and timed hint reveals from a {@code ScheduledExecutorService}.
 */
@SpringBootApplication
@EnableScheduling
public class SkribblApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkribblApplication.class, args);
    }
}
