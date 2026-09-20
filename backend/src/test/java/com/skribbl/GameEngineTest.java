package com.skribbl;

import com.skribbl.game.EngineTimings;
import com.skribbl.game.GameActionException;
import com.skribbl.game.GameEngine;
import com.skribbl.game.GameEvents;
import com.skribbl.game.GamePhase;
import com.skribbl.game.GameRecorder;
import com.skribbl.game.GameSettings;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.RoomStatus;
import com.skribbl.game.WordMode;
import com.skribbl.game.WordProvider;
import com.skribbl.game.WordSelection;
import com.skribbl.ws.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays real games against {@link GameEngine} with a real scheduler and
 * millisecond timings, recording everything the engine sends.
 *
 * <p>This is the payoff of keeping the engine free of Spring and STOMP: a
 * complete multi-round game, including timeouts and disconnects, runs in a
 * couple of seconds with no server.
 */
class GameEngineTest {

    /** A captured outbound event. {@code target} is null for room-wide broadcasts. */
    record Sent(String type, String target, Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) payload;
        }
    }

    /** Records events; optionally plays the drawer by choosing the first word. */
    static class RecordingEvents implements GameEvents {
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        final ScheduledExecutorService bots = Executors.newSingleThreadScheduledExecutor();
        volatile GameEngine engine;
        volatile boolean drawersChoose = true;

        @Override
        public void broadcast(Room room, String type, Object payload) {
            sent.add(new Sent(type, null, payload));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void sendToPlayer(Room room, Player player, String type, Object payload) {
            sent.add(new Sent(type, player.getId(), payload));
            if (drawersChoose && EventType.WORD_CHOICES.equals(type)) {
                String pick = ((List<String>) ((Map<String, Object>) payload).get("choices")).get(0);
                bots.schedule(() -> {
                    try {
                        engine.chooseWord(room, player.getId(), pick);
                    } catch (GameActionException ignored) {
                        // the turn may have moved on
                    }
                }, 20, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void broadcastState(Room room) {
            sent.add(new Sent(EventType.GAME_STATE, null,
                    Map.of("maskedWord", room.getGame().getMaskedWord())));
        }

        List<Sent> of(String type) {
            synchronized (sent) {
                return sent.stream().filter(s -> s.type().equals(type)).toList();
            }
        }

        List<Sent> all() {
            synchronized (sent) {
                return new ArrayList<>(sent);
            }
        }
    }

    private static final List<String> POOL = List.of(
            "zxalpha", "zxbravo", "zxcharlie", "zxdelta", "zxecho", "zxfoxtrot",
            "zxgolf", "zxhotel", "zxindia", "zxjuliet", "zxkilo", "zxlima");

    private static final WordProvider WORDS = (settings, exclude, count) ->
            WordSelection.pick(POOL, settings.getCustomWords(), count, exclude, new Random());

    private static final EngineTimings FAST = new EngineTimings(300, 150, 100, 300);

    private ScheduledThreadPoolExecutor scheduler;
    private RecordingEvents events;
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        scheduler = new ScheduledThreadPoolExecutor(2);
        scheduler.setRemoveOnCancelPolicy(true);
        events = new RecordingEvents();
        engine = new GameEngine(events, WORDS, GameRecorder.NO_OP, scheduler, FAST);
        events.engine = engine;
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        events.bots.shutdownNow();
    }

    private Room room(int rounds, int drawSeconds, int hints, String... names) {
        GameSettings settings = new GameSettings();
        settings.setRounds(rounds);
        settings.setDrawTimeSeconds(drawSeconds);   // below the REST minimum; fine in a test
        settings.setHints(hints);
        settings.setWordChoices(3);
        Room room = new Room("TEST01", settings);
        for (int i = 0; i < names.length; i++) {
            room.addPlayer("p" + i, names[i]);
        }
        return room;
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for condition");
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("a full 2-round, 3-player game rotates drawers correctly and ends once")
    void fullGame() throws Exception {
        Room room = room(2, 1, 2, "Asha", "Bilal", "Chitra");
        engine.startGame(room, "p0");
        await(() -> !events.of(EventType.GAME_OVER).isEmpty(), 15_000);

        List<String> drawers = events.of(EventType.ROUND_START).stream()
                .map(s -> (String) s.map().get("drawerId")).toList();
        assertEquals(List.of("p0", "p1", "p2", "p0", "p1", "p2"), drawers);

        List<Sent> ends = events.of(EventType.ROUND_END);
        assertEquals(6, ends.size());
        assertEquals(Boolean.TRUE, ends.get(5).map().get("gameOver"));

        List<String> words = ends.stream().map(e -> (String) e.map().get("word")).toList();
        assertEquals(6, new HashSet<>(words).size(), "a word was repeated: " + words);

        assertEquals(1, events.of(EventType.GAME_OVER).size());
        assertEquals(RoomStatus.FINISHED, room.getStatus());
        assertFalse(events.of(EventType.TIMER_TICK).isEmpty());
        assertFalse(events.of(EventType.HINT_REVEALED).isEmpty());
    }

    @Test
    @DisplayName("the secret word never appears in a room-wide message before the round ends")
    void secretWordNeverLeaks() throws Exception {
        Room room = room(2, 1, 3, "Asha", "Bilal");
        engine.startGame(room, "p0");
        await(() -> !events.of(EventType.GAME_OVER).isEmpty(), 15_000);

        Set<String> secrets = new HashSet<>();
        events.of(EventType.ROUND_END).forEach(e -> secrets.add((String) e.map().get("word")));

        for (Sent s : events.all()) {
            boolean mayRevealWord = s.target() != null
                    || s.type().equals(EventType.ROUND_END)
                    || s.type().equals(EventType.GAME_OVER);
            if (mayRevealWord) {
                continue;
            }
            String dump = String.valueOf(s.payload());
            for (String secret : secrets) {
                assertFalse(dump.contains(secret), s.type() + " leaked '" + secret + "'");
            }
        }
        assertTrue(events.of(EventType.WORD_CHOICES).stream().allMatch(s -> s.target() != null));
        assertTrue(events.of(EventType.WORD_CONFIRMED).stream().allMatch(s -> s.target() != null));
    }

    @Test
    @DisplayName("if the drawer never picks, a word is picked for them")
    void choiceTimeoutAutoPicks() throws Exception {
        events.drawersChoose = false;
        Room room = room(2, 1, 0, "Asha", "Bilal");
        engine.startGame(room, "p0");
        assertEquals(GamePhase.CHOOSING, room.getGame().getPhase());

        await(() -> !events.of(EventType.WORD_CONFIRMED).isEmpty(), 2_000);
        @SuppressWarnings("unchecked")
        List<String> offered = (List<String>) events.of(EventType.WORD_CHOICES).get(0).map().get("choices");
        assertTrue(offered.contains(events.of(EventType.WORD_CONFIRMED).get(0).map().get("word")));
    }

    @Test
    @DisplayName("when the drawer leaves mid-turn, the next player still gets their turn")
    void drawerLeavingDoesNotSkipNextPlayer() throws Exception {
        Room room = room(2, 2, 0, "Asha", "Bilal", "Chitra");
        engine.startGame(room, "p0");
        await(() -> "p1".equals(room.getGame().getDrawerId())
                && room.getGame().getPhase() == GamePhase.DRAWING, 8_000);

        engine.onPlayerDisconnected(room, "p1", null);
        await(() -> events.of(EventType.ROUND_END).stream()
                .anyMatch(e -> "DRAWER_LEFT".equals(e.map().get("reason"))), 3_000);
        await(() -> events.of(EventType.ROUND_START).size() >= 3, 3_000);

        assertEquals("p2", events.of(EventType.ROUND_START).get(2).map().get("drawerId"));
    }

    @Test
    @DisplayName("a drawer who refreshes inside the grace period keeps their seat and their word")
    void quickReconnectKeepsSeat() throws Exception {
        Room room = room(2, 3, 0, "Asha", "Bilal");
        room.getPlayer("p0").orElseThrow().setSessionId("old-socket");
        engine.startGame(room, "p0");
        await(() -> room.getGame().getPhase() == GamePhase.DRAWING, 2_000);
        String word = room.getGame().getCurrentWord();

        engine.onPlayerDisconnected(room, "p0", "old-socket");
        Thread.sleep(100);
        Player asha = room.getPlayer("p0").orElseThrow();
        asha.setConnected(true);
        asha.setSessionId("new-socket");
        engine.onPlayerConnected(room, asha);
        Thread.sleep(500);   // past when the removal would have fired

        assertTrue(events.of(EventType.PLAYER_LEFT).isEmpty());
        List<Sent> confirmations = events.of(EventType.WORD_CONFIRMED);
        assertEquals(word, confirmations.get(confirmations.size() - 1).map().get("word"));

        // A late close from the old socket must not knock out the new connection.
        engine.onPlayerDisconnected(room, "p0", "old-socket");
        assertTrue(room.getPlayer("p0").orElseThrow().isConnected());
    }

    @Test
    @DisplayName("dropping below two players ends the game exactly once, even as stale timers fire")
    void tooFewPlayersEndsGameOnce() throws Exception {
        Room room = room(3, 2, 0, "Asha", "Bilal");
        engine.startGame(room, "p0");
        await(() -> room.getGame().getPhase() == GamePhase.DRAWING, 2_000);

        engine.removePlayer(room, "p1");
        Thread.sleep(2_500);   // let the cancelled turn's timers try to fire

        assertEquals(1, events.of(EventType.GAME_OVER).size());
        assertEquals(1, events.of(EventType.ROUND_START).size());
    }

    @Test
    @DisplayName("the rules are enforced: host-only start, drawer-only choice, offered words only")
    void rulesAreEnforced() {
        events.drawersChoose = false;

        Room solo = room(2, 1, 0, "Asha");
        assertEquals(GameActionException.Reason.NOT_ENOUGH_PLAYERS,
                assertThrows(GameActionException.class, () -> engine.startGame(solo, "p0")).getReason());

        Room room = room(2, 2, 0, "Asha", "Bilal");
        assertEquals(GameActionException.Reason.NOT_HOST,
                assertThrows(GameActionException.class, () -> engine.startGame(room, "p1")).getReason());

        engine.startGame(room, "p0");
        assertEquals(GameActionException.Reason.ALREADY_STARTED,
                assertThrows(GameActionException.class, () -> engine.startGame(room, "p0")).getReason());

        String offered = room.getGame().getWordChoices().get(0);
        assertEquals(GameActionException.Reason.NOT_DRAWER,
                assertThrows(GameActionException.class, () -> engine.chooseWord(room, "p1", offered)).getReason());
        assertEquals(GameActionException.Reason.INVALID_WORD,
                assertThrows(GameActionException.class, () -> engine.chooseWord(room, "p0", "my own word")).getReason());

        engine.chooseWord(room, "p0", offered.toUpperCase());
        assertEquals(offered, room.getGame().getCurrentWord());
        assertEquals(GameActionException.Reason.WRONG_PHASE,
                assertThrows(GameActionException.class, () -> engine.chooseWord(room, "p0", offered)).getReason());
    }

    @Test
    @DisplayName("hidden mode shows no blanks and gives no hints; combination mode offers word pairs")
    void wordModes() throws Exception {
        Room hidden = room(2, 1, 3, "Asha", "Bilal");
        hidden.getSettings().setWordMode(WordMode.HIDDEN);
        engine.startGame(hidden, "p0");
        await(() -> hidden.getGame().getPhase() == GamePhase.DRAWING, 2_000);
        assertEquals("?", hidden.getGame().getMaskedWord());
        Thread.sleep(1_200);
        assertTrue(events.of(EventType.HINT_REVEALED).isEmpty());

        events.drawersChoose = false;
        Room combo = room(2, 1, 0, "Asha", "Bilal");
        combo.getSettings().setWordMode(WordMode.COMBINATION);
        engine.startGame(combo, "p0");
        assertTrue(combo.getGame().getWordChoices().stream().allMatch(w -> w.split(" ").length == 2));
    }

    @Test
    @DisplayName("the host can start a fresh game after one finishes, with scores reset")
    void playAgain() throws Exception {
        Room room = room(2, 1, 0, "Asha", "Bilal");
        engine.startGame(room, "p0");
        await(() -> !events.of(EventType.GAME_OVER).isEmpty(), 10_000);

        engine.startGame(room, room.getHostId());
        assertTrue(room.getGame().isInProgress());
        assertTrue(room.getPlayers().stream().allMatch(p -> p.getScore() == 0));
    }
}
