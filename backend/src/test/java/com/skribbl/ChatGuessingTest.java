package com.skribbl;

import com.skribbl.game.EngineTimings;
import com.skribbl.game.GameEngine;
import com.skribbl.game.GameEvents;
import com.skribbl.game.GamePhase;
import com.skribbl.game.GameRecorder;
import com.skribbl.game.GameSettings;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.WordProvider;
import com.skribbl.ws.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guessing and chat. The central property: while a turn is being guessed, the
 * secret word never appears in anything sent to the whole room.
 */
class ChatGuessingTest {

    record Sent(String type, String target, Map<String, Object> payload) {
        boolean isPrivate() {
            return target != null;
        }
    }

    static class RecordingEvents implements GameEvents {
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        @SuppressWarnings("unchecked")
        public void broadcast(Room room, String type, Object payload) {
            sent.add(new Sent(type, null, (Map<String, Object>) payload));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void sendToPlayer(Room room, Player player, String type, Object payload) {
            sent.add(new Sent(type, player.getId(), (Map<String, Object>) payload));
        }

        @Override
        public void broadcastState(Room room) {
            sent.add(new Sent(EventType.GAME_STATE, null, Map.of()));
        }

        List<Sent> since(int mark) {
            synchronized (sent) {
                return new ArrayList<>(sent.subList(mark, sent.size()));
            }
        }

        List<Sent> of(String type) {
            synchronized (sent) {
                return sent.stream().filter(s -> s.type().equals(type)).toList();
            }
        }
    }

    private ScheduledThreadPoolExecutor scheduler;
    private RecordingEvents events;
    private GameEngine engine;
    private Room room;

    /** Every drawer is offered `word` first, so tests know the answer. */
    private static WordProvider always(String word) {
        return (settings, exclude, count) -> {
            List<String> out = new ArrayList<>(List.of(word));
            for (int i = 1; i < count; i++) {
                out.add(word + "x" + i);
            }
            return out;
        };
    }

    private void startDrawing(String word, long cooldownMs, String... names) {
        engine = new GameEngine(events, always(word), GameRecorder.NO_OP, scheduler,
                new EngineTimings(60_000, 150, 60_000, 60_000, cooldownMs));
        GameSettings settings = new GameSettings();
        settings.setDrawTimeSeconds(120);
        settings.setHints(0);
        room = new Room("CHAT01", settings);
        for (int i = 0; i < names.length; i++) {
            room.addPlayer("p" + i, names[i]);
        }
        engine.startGame(room, "p0");
        engine.chooseWord(room, "p0", word);
    }

    @BeforeEach
    void setUp() {
        scheduler = new ScheduledThreadPoolExecutor(2);
        scheduler.setRemoveOnCancelPolicy(true);
        events = new RecordingEvents();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("a correct guess scores, is announced without the word, and reveals it only to the guesser")
    void correctGuess() {
        startDrawing("rocket", 0, "Asha", "Bilal", "Chitra");
        int mark = events.sent.size();

        engine.handleChat(room, "p1", "  ROCKET ");
        List<Sent> out = events.since(mark);

        Sent result = out.stream().filter(s -> s.type().equals(EventType.GUESS_RESULT)).findFirst().orElseThrow();
        assertFalse(result.isPrivate());
        assertEquals(true, result.payload().get("correct"));
        assertTrue((Integer) result.payload().get("points") > 0);
        assertFalse(String.valueOf(result.payload()).contains("rocket"), "announcement must not contain the word");

        Sent reveal = out.stream().filter(s -> s.type().equals(EventType.WORD_CONFIRMED)).findFirst().orElseThrow();
        assertEquals("p1", reveal.target());
        assertTrue(out.stream().noneMatch(s -> s.type().equals(EventType.CHAT_MESSAGE)));
        assertEquals(GamePhase.DRAWING, room.getGame().getPhase(), "Chitra is still guessing");
    }

    @Test
    @DisplayName("earlier guesses score more, and the turn ends early once everyone has guessed")
    void allGuessedEndsTurnEarly() {
        startDrawing("rocket", 0, "Asha", "Bilal", "Chitra");
        engine.handleChat(room, "p1", "rocket");
        engine.handleChat(room, "p2", "rocket");

        int bilal = room.getPlayer("p1").orElseThrow().getScore();
        int chitra = room.getPlayer("p2").orElseThrow().getScore();
        assertTrue(bilal > chitra && chitra > 0);

        List<Sent> ends = events.of(EventType.ROUND_END);
        assertEquals(1, ends.size());
        assertEquals("ALL_GUESSED", ends.get(0).payload().get("reason"));
        assertEquals(50, ends.get(0).payload().get("drawerPoints"));
    }

    @Test
    @DisplayName("a message containing the word is withheld from the room; a close guess gets a private hint")
    void leakingAndCloseGuesses() {
        startDrawing("rocket", 0, "Asha", "Bilal", "Chitra");

        int mark = events.sent.size();
        engine.handleChat(room, "p1", "is it a rocket??");
        List<Sent> out = events.since(mark);
        assertTrue(out.stream().allMatch(Sent::isPrivate), "must not reach the room");
        assertEquals(EventType.SYSTEM_MESSAGE, out.get(0).type());
        assertFalse(room.getPlayer("p1").orElseThrow().hasGuessedCurrentRound());

        mark = events.sent.size();
        engine.handleChat(room, "p1", "rockt");
        out = events.since(mark);
        assertTrue(out.stream().anyMatch(s -> s.type().equals(EventType.CHAT_MESSAGE) && !s.isPrivate()));
        Sent close = out.stream().filter(s -> s.type().equals(EventType.GUESS_RESULT)).findFirst().orElseThrow();
        assertEquals("p1", close.target());
        assertEquals(true, close.payload().get("close"));

        mark = events.sent.size();
        engine.handleChat(room, "p1", "I want to win");
        assertTrue(events.since(mark).stream().anyMatch(s -> !s.isPrivate()), "no false positive on 'want'");
    }

    @Test
    @DisplayName("the drawer cannot say the word, even spaced out")
    void drawerCannotSayTheWord() {
        startDrawing("rocket", 0, "Asha", "Bilal", "Chitra");
        int mark = events.sent.size();
        engine.handleChat(room, "p0", "it's a rocket lol");
        engine.handleChat(room, "p0", "r o c k e t");
        assertTrue(events.since(mark).stream().allMatch(Sent::isPrivate));

        mark = events.sent.size();
        engine.handleChat(room, "p0", "good luck everyone");
        assertTrue(events.since(mark).stream().anyMatch(s -> !s.isPrivate()));
    }

    @Test
    @DisplayName("players who already guessed chat only with the drawer and each other")
    void guessedPlayersChatPrivately() {
        startDrawing("rocket", 0, "Asha", "Bilal", "Chitra", "Dev");
        engine.handleChat(room, "p1", "rocket");

        int mark = events.sent.size();
        engine.handleChat(room, "p1", "haha it was rocket");
        List<Sent> out = events.since(mark);

        assertTrue(out.stream().allMatch(Sent::isPrivate));
        Set<String> recipients = new TreeSet<>();
        out.forEach(s -> recipients.add(s.target()));
        assertEquals(Set.of("p0", "p1"), recipients);

        int score = room.getPlayer("p1").orElseThrow().getScore();
        engine.handleChat(room, "p1", "rocket");
        assertEquals(score, room.getPlayer("p1").orElseThrow().getScore(), "no double scoring");
    }

    @Test
    @DisplayName("chat is cleaned, capped at 100 characters and rate-limited per player")
    void chatHygiene() throws InterruptedException {
        engine = new GameEngine(events, always("rocket"), GameRecorder.NO_OP, scheduler,
                new EngineTimings(60_000, 150, 60_000, 60_000, 300));
        room = new Room("LOBBY1", new GameSettings());
        room.addPlayer("p0", "Asha");
        room.addPlayer("p1", "Bilal");

        engine.handleChat(room, "p1", "hello   \n  everyone\t!");
        engine.handleChat(room, "p1", "too fast");
        engine.handleChat(room, "p0", "other player unaffected");
        List<Sent> chat = events.of(EventType.CHAT_MESSAGE);
        assertEquals(2, chat.size());
        assertEquals("hello everyone !", chat.get(0).payload().get("text"));

        Thread.sleep(350);
        engine.handleChat(room, "p1", "x".repeat(500));
        chat = events.of(EventType.CHAT_MESSAGE);
        assertEquals(100, ((String) chat.get(2).payload().get("text")).length());
    }

    @Test
    @DisplayName("spacing does not matter for multi-word answers")
    void multiWordAnswers() {
        startDrawing("ice cream", 0, "Asha", "Bilal", "Chitra");
        engine.handleChat(room, "p1", "icecream");
        assertTrue(room.getPlayer("p1").orElseThrow().hasGuessedCurrentRound());

        int mark = events.sent.size();
        engine.handleChat(room, "p2", "maybe icecream cone");
        assertTrue(events.since(mark).stream().allMatch(Sent::isPrivate));
        assertNotNull(room.getGame().getCurrentWord());
    }
}
