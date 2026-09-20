package com.skribbl.service;

import com.skribbl.domain.MessageType;
import com.skribbl.domain.RoundStatus;
import com.skribbl.game.ChatKind;
import com.skribbl.game.EndReason;
import com.skribbl.game.GameRecorder;
import com.skribbl.game.Room;
import com.skribbl.game.TurnRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Writes game history to MySQL without ever making the game wait.
 *
 * <p>The engine calls these hooks while holding a room lock, so they must
 * return immediately. Each call is handed to a <strong>single</strong> background
 * thread. Single, not a pool, on purpose: it keeps writes in the order they
 * happened, so "round started" is always written before "word chosen" for the
 * same turn. The round's database id is passed between those two writes through
 * the turn's {@link TurnRecord#getDbId()}, read at execution time rather than
 * submission time.
 */
@Component
public class DbGameRecorder implements GameRecorder {

    private static final Logger log = LoggerFactory.getLogger(DbGameRecorder.class);

    private final PersistenceService persistence;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "game-db-writer");
        thread.setDaemon(true);
        return thread;
    });

    public DbGameRecorder(PersistenceService persistence) {
        this.persistence = persistence;
    }

    @Override
    public void gameStarted(Room room) {
        submit(() -> persistence.saveGameStarted(room));
    }

    @Override
    public void turnStarted(Room room, TurnRecord turn) {
        submit(() -> persistence
                .saveRoundStarted(room, turn.getDrawerId(), turn.getRoundNumber(), turn.getTurnIndex())
                .ifPresent(id -> turn.getDbId().set(id)));
    }

    @Override
    public void wordChosen(Room room, TurnRecord turn, String word) {
        submit(() -> persistence.saveWordChosen(turn.getDbId().get(), word));
    }

    @Override
    public void turnEnded(Room room, TurnRecord turn, EndReason reason,
                          int correctGuessCount, Map<String, Integer> scores) {
        RoundStatus status = switch (reason) {
            case TIME_UP -> RoundStatus.TIMED_OUT;
            case ALL_GUESSED -> RoundStatus.ALL_GUESSED;
            case DRAWER_LEFT -> RoundStatus.ABANDONED;
        };
        submit(() -> persistence.saveRoundEnded(
                room, turn.getDbId().get(), status, correctGuessCount, scores));
    }

    @Override
    public void gameOver(Room room, Map<String, Integer> scores) {
        submit(() -> persistence.saveGameOver(room, scores));
    }

    @Override
    public void playerLeft(Room room, String playerId) {
        submit(() -> persistence.savePlayerLeft(room, playerId));
    }

    @Override
    public void hostChanged(Room room, String newHostId) {
        submit(() -> persistence.saveHostChanged(room, newHostId));
    }

    @Override
    public void message(Room room, TurnRecord turn, String playerId, String text,
                        ChatKind kind, int points) {
        MessageType type = switch (kind) {
            case CHAT -> MessageType.CHAT;
            case GUESS -> MessageType.GUESS;
            case CORRECT_GUESS -> MessageType.CORRECT_GUESS;
        };
        submit(() -> persistence.saveMessage(
                room, turn == null ? null : turn.getDbId().get(), playerId, text, type, points));
    }

    private void submit(Runnable task) {
        try {
            writer.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("Background game write failed", e);
                }
            });
        } catch (RuntimeException e) {
            // Rejected during shutdown; history is best-effort by design.
            log.warn("Dropped a game write during shutdown");
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        writer.shutdown();
        // Give queued writes a moment to land so the final scores are saved.
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
