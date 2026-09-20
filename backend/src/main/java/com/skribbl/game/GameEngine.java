package com.skribbl.game;

import com.skribbl.ws.EventType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.skribbl.game.GameViews.payload;

/**
 * Drives every room's game: turns, timers, word choice, hints, round ends,
 * game over, and players dropping out.
 *
 * <p><strong>No Spring, no sockets, no database.</strong> The engine talks to the
 * outside world only through three interfaces — {@link GameEvents},
 * {@link WordProvider} and {@link GameRecorder} — and takes its scheduler and
 * timings as constructor arguments. That is what lets a whole game be played in
 * a unit test in two seconds with fakes, which is the only realistic way to test
 * timer-driven code.
 *
 * <h2>Concurrency model</h2>
 * A room is touched by HTTP threads (start), WebSocket threads (word chosen,
 * disconnects) and scheduler threads (timers). The rules:
 * <ol>
 *   <li>Every public method, and every timer callback, runs holding the room's
 *       lock. One room's game can never be mutated by two threads at once.</li>
 *   <li>Every timer is stamped with the id of the turn that created it, and
 *       checks it before acting (see {@link TurnRecord}). Cancelling a future is
 *       not enough on its own: a callback already blocked on the lock cannot be
 *       cancelled, and would otherwise end the <em>next</em> turn early.</li>
 *   <li>Every timer callback catches its own exceptions. An exception escaping a
 *       fixed-rate task silently cancels every future run of it — the countdown
 *       would just stop, with nothing in the logs.</li>
 *   <li>Persistence is fire-and-forget through {@link GameRecorder}; the lock is
 *       never held across a database round-trip.</li>
 * </ol>
 *
 * <h2>Turn lifecycle</h2>
 * <pre>
 *  startGame ─▶ beginNextTurn ─▶ CHOOSING ──word chosen / timeout──▶ startDrawing ─▶ DRAWING
 *                    ▲                                                                  │
 *                    │                                          time up / all guessed / drawer left
 *                    │                                                                  ▼
 *                    └──────── pause (roundEndPauseMs) ◀─── more turns ◀──────────── endTurn
 *                                                                                       │
 *                                                                    last turn ─▶ pause ─▶ finishGame
 * </pre>
 */
public final class GameEngine {

    private static final System.Logger LOG = System.getLogger(GameEngine.class.getName());

    private final GameEvents events;
    private final WordProvider words;
    private final GameRecorder recorder;
    private final ScheduledExecutorService scheduler;
    private final EngineTimings timings;

    /** Pending "remove this player" timers, keyed by room code + player id. */
    private final Map<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();

    /** Longest chat line accepted; longer ones are cut. */
    public static final int MAX_CHAT_LENGTH = 100;

    /** When each player last chatted, keyed like {@link #pendingRemovals}, for the cooldown. */
    private final Map<String, Long> lastMessageAt = new ConcurrentHashMap<>();

    public GameEngine(GameEvents events,
                      WordProvider words,
                      GameRecorder recorder,
                      ScheduledExecutorService scheduler,
                      EngineTimings timings) {
        this.events = events;
        this.words = words;
        this.recorder = recorder;
        this.scheduler = scheduler;
        this.timings = timings;
    }

    // =====================================================================
    // Commands from players
    // =====================================================================

    /**
     * Host starts the game (or starts a new one after the last finished).
     *
     * @throws GameActionException if the caller is not the host, the game is
     *         already running, or fewer than two players are connected
     */
    public void startGame(Room room, String requesterId) {
        room.lock();
        try {
            Game game = room.getGame();
            if (!room.isHost(requesterId)) {
                throw new GameActionException(GameActionException.Reason.NOT_HOST,
                        "Only the host can start the game");
            }
            if (game.isInProgress()) {
                throw new GameActionException(GameActionException.Reason.ALREADY_STARTED,
                        "The game is already running");
            }
            if (room.getConnectedPlayers().size() < GameSettings.MIN_PLAYERS) {
                throw new GameActionException(GameActionException.Reason.NOT_ENOUGH_PLAYERS,
                        "At least " + GameSettings.MIN_PLAYERS + " players are needed to start");
            }

            game.cancelTimers();
            game.start();
            recorder.gameStarted(room);
            events.broadcast(room, EventType.SYSTEM_MESSAGE, payload("text", "The game has started!"));
            beginNextTurn(room);
        } finally {
            room.unlock();
        }
    }

    /**
     * The drawer picks one of the offered words.
     *
     * @throws GameActionException if the caller is not the drawer, it is not the
     *         choosing phase, or the word was not one of the choices offered
     */
    public void chooseWord(Room room, String playerId, String word) {
        room.lock();
        try {
            Game game = room.getGame();
            if (game.getPhase() != GamePhase.CHOOSING) {
                throw new GameActionException(GameActionException.Reason.WRONG_PHASE,
                        "Words can only be chosen at the start of a turn");
            }
            if (!playerId.equals(game.getDrawerId())) {
                throw new GameActionException(GameActionException.Reason.NOT_DRAWER,
                        "Only the drawer can choose the word");
            }
            // Match against what was actually offered. Accepting any string here
            // would let a modified client draw a word of its own choosing.
            String chosen = null;
            for (String option : game.getWordChoices()) {
                if (option.equalsIgnoreCase(word == null ? "" : word.trim())) {
                    chosen = option;
                    break;
                }
            }
            if (chosen == null) {
                throw new GameActionException(GameActionException.Reason.INVALID_WORD,
                        "That word was not one of your choices");
            }
            startDrawing(room, chosen);
        } finally {
            room.unlock();
        }
    }

    // =====================================================================
    // Chat and guessing
    // =====================================================================

    /**
     * A player typed something. There is one input box, as in skribbl.io, and
     * the server decides what the text is.
     *
     * <p>During a drawing turn, for a player still guessing:
     * <ul>
     *   <li><b>the word</b> → points, a room-wide "X guessed it" <em>without</em>
     *       the word, and the word sent privately to that player;</li>
     *   <li><b>contains the word</b> ("is it rocket?") → not shown to anyone,
     *       with a private note, because broadcasting it gives the answer away;</li>
     *   <li><b>one letter off</b> → shown as a normal guess, plus a private
     *       "close!" to the guesser only;</li>
     *   <li><b>anything else</b> → shown to everyone as a guess.</li>
     * </ul>
     * Players who have already guessed chat only among themselves and the
     * drawer, so they cannot give the word away. The drawer may chat, but not
     * say the word. Outside a drawing turn, everything is plain chat.
     */
    public void handleChat(Room room, String playerId, String rawText) {
        String text = cleanChat(rawText);
        if (text.isEmpty()) {
            return;
        }
        room.lock();
        try {
            Optional<Player> maybePlayer = room.getPlayer(playerId);
            if (maybePlayer.isEmpty() || tooSoon(room, playerId)) {
                return;
            }
            Player player = maybePlayer.get();
            Game game = room.getGame();

            if (game.getPhase() != GamePhase.DRAWING) {
                broadcastChat(room, player, text, "chat");
                recorder.message(room, null, playerId, text, ChatKind.CHAT, 0);
                return;
            }

            TurnRecord turn = game.getCurrentTurn();

            if (playerId.equals(game.getDrawerId())) {
                if (game.mentionsWord(text, true)) {
                    notifyPlayer(room, player, "You can't say the word while you're drawing!");
                    return;
                }
                broadcastChat(room, player, text, "chat");
                recorder.message(room, turn, playerId, text, ChatKind.CHAT, 0);
                return;
            }

            if (player.hasGuessedCurrentRound()) {
                sendToGuessedGroup(room, player, text);
                recorder.message(room, turn, playerId, text, ChatKind.CHAT, 0);
                return;
            }

            if (game.isCorrectGuess(text)) {
                onCorrectGuess(room, player, text);
                return;
            }

            if (game.mentionsWord(text, false)) {
                notifyPlayer(room, player,
                        "Type just the word to guess — that message wasn't shown, so it can't give the answer away.");
                return;
            }

            broadcastChat(room, player, text, "guess");
            if (game.isCloseGuess(text)) {
                events.sendToPlayer(room, player, EventType.GUESS_RESULT, payload(
                        "correct", false,
                        "close", true,
                        "playerId", playerId,
                        "playerName", player.getName(),
                        "text", text));
            }
            recorder.message(room, turn, playerId, text, ChatKind.GUESS, 0);
        } finally {
            room.unlock();
        }
    }

    private void onCorrectGuess(Room room, Player player, String text) {
        Game game = room.getGame();
        int points = game.registerCorrectGuess(player);
        int order = game.getCorrectGuessers().size();
        recorder.message(room, game.getCurrentTurn(), player.getId(), text, ChatKind.CORRECT_GUESS, points);

        // Everyone learns that they got it and how many points — never the word.
        events.broadcast(room, EventType.GUESS_RESULT, payload(
                "correct", true,
                "close", false,
                "playerId", player.getId(),
                "playerName", player.getName(),
                "points", points,
                "order", order));
        // The guesser gets the word itself, so their screen can show it.
        events.sendToPlayer(room, player, EventType.WORD_CONFIRMED,
                payload("word", game.getCurrentWord()));
        events.broadcastState(room);

        if (game.allGuessersCorrect()) {
            endTurn(room, EndReason.ALL_GUESSED);
        }
    }

    private void broadcastChat(Room room, Player player, String text, String kind) {
        events.broadcast(room, EventType.CHAT_MESSAGE, payload(
                "playerId", player.getId(),
                "playerName", player.getName(),
                "text", text,
                "kind", kind,
                "guessedOnly", false));
    }

    /** Chat from someone who already guessed: visible only to the drawer and others who guessed. */
    private void sendToGuessedGroup(Room room, Player sender, String text) {
        String drawerId = room.getGame().getDrawerId();
        Map<String, Object> message = payload(
                "playerId", sender.getId(),
                "playerName", sender.getName(),
                "text", text,
                "kind", "chat",
                "guessedOnly", true);
        for (Player p : room.getPlayers()) {
            if (p.hasGuessedCurrentRound() || p.getId().equals(drawerId)) {
                events.sendToPlayer(room, p, EventType.CHAT_MESSAGE, message);
            }
        }
    }

    private void notifyPlayer(Room room, Player player, String text) {
        events.sendToPlayer(room, player, EventType.SYSTEM_MESSAGE, payload("text", text));
    }

    /** Per-player cooldown, so one player cannot flood everyone else's chat. */
    private boolean tooSoon(Room room, String playerId) {
        long now = System.currentTimeMillis();
        String key = removalKey(room, playerId);
        Long previous = lastMessageAt.get(key);
        if (previous != null && now - previous < timings.chatCooldownMs()) {
            return true;
        }
        lastMessageAt.put(key, now);
        return false;
    }

    /** Trims, collapses whitespace, strips control characters and caps the length. */
    static String cleanChat(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() <= MAX_CHAT_LENGTH ? cleaned : cleaned.substring(0, MAX_CHAT_LENGTH);
    }

    // =====================================================================
    // Connection lifecycle
    // =====================================================================

    /**
     * A player's socket dropped. They keep their seat for
     * {@link EngineTimings#reconnectGraceMs()} so a page refresh or a network
     * blip does not throw them out of the game.
     *
     * @param sessionId the socket that closed; ignored if the player has since
     *                  connected on a newer socket (refresh races often deliver
     *                  the old socket's close after the new socket's join)
     */
    public void onPlayerDisconnected(Room room, String playerId, String sessionId) {
        room.lock();
        try {
            Optional<Player> maybePlayer = room.getPlayer(playerId);
            if (maybePlayer.isEmpty()) {
                return;
            }
            Player player = maybePlayer.get();
            if (sessionId != null && !sessionId.equals(player.getSessionId())) {
                return;
            }
            player.setConnected(false);
            events.broadcastState(room);

            String key = removalKey(room, playerId);
            ScheduledFuture<?> removal = scheduler.schedule(
                    () -> removeIfStillGone(room, playerId, key),
                    timings.reconnectGraceMs(), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = pendingRemovals.put(key, removal);
            if (previous != null) {
                previous.cancel(false);
            }
        } finally {
            room.unlock();
        }
    }

    /**
     * A player's socket joined (first connect or reconnect). Cancels any pending
     * removal and re-sends private state they would otherwise have lost — a
     * drawer who refreshes mid-turn needs their word back.
     *
     * <p>The caller has already marked the player connected and recorded the
     * new session id.
     */
    public void onPlayerConnected(Room room, Player player) {
        room.lock();
        try {
            ScheduledFuture<?> pending = pendingRemovals.remove(removalKey(room, player.getId()));
            if (pending != null) {
                pending.cancel(false);
            }

            Game game = room.getGame();
            if (player.getId().equals(game.getDrawerId())) {
                if (game.getPhase() == GamePhase.CHOOSING) {
                    events.sendToPlayer(room, player, EventType.WORD_CHOICES, payload(
                            "choices", game.getWordChoices(),
                            "timeoutSeconds", timings.choiceTimeoutMs() / 1000));
                } else if (game.getPhase() == GamePhase.DRAWING) {
                    events.sendToPlayer(room, player, EventType.WORD_CONFIRMED,
                            payload("word", game.getCurrentWord()));
                }
            }
        } finally {
            room.unlock();
        }
    }

    /** Removes a player immediately, e.g. when they click Leave. */
    public void removePlayer(Room room, String playerId) {
        room.lock();
        try {
            ScheduledFuture<?> pending = pendingRemovals.remove(removalKey(room, playerId));
            if (pending != null) {
                pending.cancel(false);
            }
            removeNow(room, playerId);
        } finally {
            room.unlock();
        }
    }

    // =====================================================================
    // Turn lifecycle (all private methods assume the room lock is held)
    // =====================================================================

    private void beginNextTurn(Room room) {
        Game game = room.getGame();
        game.cancelTimers();

        // Find a drawer who is actually here. Handing the pen to someone
        // mid-reconnect would waste a whole turn staring at a blank canvas.
        int attempts = Math.max(1, game.getTurnOrder().size());
        while (true) {
            if (game.normaliseTurnPosition()
                    || room.getConnectedPlayers().size() < GameSettings.MIN_PLAYERS) {
                finishGame(room);
                return;
            }
            game.beginTurn();
            Optional<Player> drawer = room.getPlayer(game.getDrawerId());
            if (drawer.isPresent() && drawer.get().isConnected()) {
                break;
            }
            if (--attempts <= 0 || game.advanceTurn()) {
                finishGame(room);
                return;
            }
        }

        Player drawer = room.getPlayer(game.getDrawerId()).orElseThrow();
        TurnRecord turn = game.getCurrentTurn();
        List<String> choices = pickChoices(room);
        game.setWordChoices(choices);
        recorder.turnStarted(room, turn);

        events.broadcastState(room);
        events.broadcast(room, EventType.ROUND_START, payload(
                "round", game.getCurrentRound(),
                "totalRounds", room.getSettings().getRounds(),
                "drawerId", drawer.getId(),
                "drawerName", drawer.getName(),
                "choiceTimeoutSeconds", timings.choiceTimeoutMs() / 1000));
        events.sendToPlayer(room, drawer, EventType.WORD_CHOICES, payload(
                "choices", choices,
                "timeoutSeconds", timings.choiceTimeoutMs() / 1000));
        events.broadcast(room, EventType.SYSTEM_MESSAGE,
                payload("text", drawer.getName() + " is choosing a word"));

        game.setChoiceTimerFuture(schedule(room, turn.getTurnId(), timings.choiceTimeoutMs(),
                () -> autoPickWord(room)));
    }

    private List<String> pickChoices(Room room) {
        GameSettings settings = room.getSettings();
        int count = settings.getWordChoices();
        if (settings.getWordMode() == WordMode.COMBINATION) {
            // Combination mode: each option is two words drawn together,
            // e.g. "rocket penguin".
            List<String> raw = words.pick(settings, room.getGame().getUsedWords(), count * 2);
            List<String> combined = new ArrayList<>();
            for (int i = 0; i + 1 < raw.size() && combined.size() < count; i += 2) {
                combined.add(raw.get(i) + " " + raw.get(i + 1));
            }
            return combined.isEmpty() ? raw : combined;
        }
        return words.pick(settings, room.getGame().getUsedWords(), count);
    }

    /** The drawer let the choice timer run out; pick for them so the game moves on. */
    private void autoPickWord(Room room) {
        Game game = room.getGame();
        if (game.getPhase() != GamePhase.CHOOSING || game.getWordChoices().isEmpty()) {
            return;
        }
        List<String> choices = game.getWordChoices();
        String word = choices.get((int) (Math.random() * choices.size()));
        startDrawing(room, word);
    }

    private void startDrawing(Room room, String word) {
        Game game = room.getGame();
        game.cancelTimers();
        game.confirmWord(word);

        TurnRecord turn = game.getCurrentTurn();
        long turnId = turn.getTurnId();
        recorder.wordChosen(room, turn, word);

        room.getPlayer(game.getDrawerId()).ifPresent(drawer -> {
            events.sendToPlayer(room, drawer, EventType.WORD_CONFIRMED, payload("word", word));
            events.broadcast(room, EventType.SYSTEM_MESSAGE,
                    payload("text", drawer.getName() + " is drawing now!"));
        });
        events.broadcastState(room);

        GameSettings settings = room.getSettings();
        long drawMs = settings.getDrawTimeSeconds() * 1000L;

        game.setRoundTimerFuture(schedule(room, turnId, drawMs,
                () -> endTurn(room, EndReason.TIME_UP)));

        game.setTickFuture(scheduleRepeating(room, turnId, timings.tickMs(), timings.tickMs(), () -> {
            if (game.getPhase() == GamePhase.DRAWING) {
                events.broadcast(room, EventType.TIMER_TICK,
                        payload("remainingSeconds", game.getRemainingSeconds()));
            }
        }));

        int hints = settings.getHints();
        if (hints > 0 && settings.getWordMode() != WordMode.HIDDEN) {
            // Spread the hints evenly: with 2 hints over 90s, letters appear at
            // 30s and 60s, never in the final stretch when they would be free points.
            long interval = drawMs / (hints + 1);
            AtomicInteger given = new AtomicInteger();
            game.setHintTimerFuture(scheduleRepeating(room, turnId, interval, interval, () -> {
                if (game.getPhase() != GamePhase.DRAWING || given.get() >= hints) {
                    return;
                }
                Optional<Integer> revealed = game.revealNextHint();
                if (revealed.isEmpty()) {
                    given.set(hints);   // half the word is showing; stop here
                    return;
                }
                given.incrementAndGet();
                events.broadcast(room, EventType.HINT_REVEALED, payload(
                        "maskedWord", game.getMaskedWord(),
                        "hintsRemaining", hints - given.get()));
            }));
        }
    }

    /**
     * Ends the current turn. Safe to call more than once: only the first call in
     * a turn does anything.
     */
    private void endTurn(Room room, EndReason reason) {
        Game game = room.getGame();
        if (game.getPhase() != GamePhase.DRAWING && game.getPhase() != GamePhase.CHOOSING) {
            return;
        }
        game.cancelTimers();

        TurnRecord turn = game.getCurrentTurn();
        String word = game.getCurrentWord();
        int correct = game.getCorrectGuessers().size();
        int drawerPoints = reason == EndReason.DRAWER_LEFT ? 0 : game.awardDrawer();

        boolean over = game.advanceTurn();
        recorder.turnEnded(room, turn, reason, correct, scoreSnapshot(room));

        String nextDrawerId = over ? null : peekNextDrawer(game);
        events.broadcast(room, EventType.ROUND_END, payload(
                "word", word == null ? "" : word,
                "reason", reason.name(),
                "drawerId", turn.getDrawerId(),
                "drawerPoints", drawerPoints,
                "correctGuessCount", correct,
                "players", GameViews.players(room),
                "nextDrawerId", nextDrawerId,
                "gameOver", over,
                "pauseSeconds", timings.roundEndPauseMs() / 1000));
        events.broadcastState(room);

        // Leave the answer on screen for a moment before moving on. The
        // callback is stamped with this turn's id, so if a new game starts
        // during the pause this stale continuation does nothing.
        game.setNextTurnFuture(schedule(room, turn.getTurnId(), timings.roundEndPauseMs(), () -> {
            if (over) {
                finishGame(room);
            } else {
                beginNextTurn(room);
            }
        }));
    }

    private String peekNextDrawer(Game game) {
        List<String> order = game.getTurnOrder();
        if (order.isEmpty()) {
            return null;
        }
        return order.get(game.getTurnIndex() % order.size());
    }

    private void finishGame(Room room) {
        Game game = room.getGame();
        game.cancelTimers();
        if (game.getPhase() != GamePhase.GAME_OVER) {
            game.finish();
        }
        if (!game.markResultsAnnounced()) {
            return;
        }

        List<Map<String, Object>> leaderboard = GameViews.leaderboard(room);
        List<Map<String, Object>> winners = new ArrayList<>();
        if (!leaderboard.isEmpty()) {
            Object top = leaderboard.get(0).get("score");
            for (Map<String, Object> entry : leaderboard) {
                if (entry.get("score").equals(top)) {
                    winners.add(entry);   // ties share the win
                }
            }
        }

        recorder.gameOver(room, scoreSnapshot(room));
        events.broadcastState(room);
        events.broadcast(room, EventType.GAME_OVER, payload(
                "winner", winners.isEmpty() ? null : winners.get(0),
                "winners", winners,
                "leaderboard", leaderboard));
    }

    // =====================================================================
    // Player removal
    // =====================================================================

    private void removeIfStillGone(Room room, String playerId, String key) {
        room.lock();
        try {
            pendingRemovals.remove(key);
            Optional<Player> player = room.getPlayer(playerId);
            if (player.isPresent() && !player.get().isConnected()) {
                removeNow(room, playerId);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Removing player " + playerId + " failed", e);
        } finally {
            room.unlock();
        }
    }

    private void removeNow(Room room, String playerId) {
        Optional<Player> maybePlayer = room.getPlayer(playerId);
        if (maybePlayer.isEmpty()) {
            return;
        }
        Player player = maybePlayer.get();
        Game game = room.getGame();
        String previousHost = room.getHostId();
        boolean wasActiveDrawer = playerId.equals(game.getDrawerId())
                && (game.getPhase() == GamePhase.CHOOSING || game.getPhase() == GamePhase.DRAWING);

        lastMessageAt.remove(removalKey(room, playerId));

        // Rotation first: it needs to see the phase before anything changes it.
        game.removeFromRotation(playerId);
        room.removePlayer(playerId);
        recorder.playerLeft(room, playerId);

        events.broadcast(room, EventType.PLAYER_LEFT, payload(
                "playerId", playerId,
                "playerName", player.getName(),
                "players", GameViews.players(room)));
        String newHost = room.getHostId();
        if (newHost != null && !newHost.equals(previousHost)) {
            recorder.hostChanged(room, newHost);
            events.broadcast(room, EventType.HOST_CHANGED, payload("hostId", newHost));
        }
        events.broadcast(room, EventType.SYSTEM_MESSAGE,
                payload("text", player.getName() + " left the room"));

        if (room.isEmpty()) {
            game.cancelTimers();
            return;
        }
        if (!game.isInProgress()) {
            return;
        }
        if (room.getConnectedPlayers().size() < GameSettings.MIN_PLAYERS) {
            events.broadcast(room, EventType.SYSTEM_MESSAGE,
                    payload("text", "Not enough players left — game over"));
            finishGame(room);
            return;
        }
        if (wasActiveDrawer) {
            endTurn(room, EndReason.DRAWER_LEFT);
        } else if (game.getPhase() == GamePhase.DRAWING && game.allGuessersCorrect()) {
            // The last person still guessing left; everyone remaining has it.
            endTurn(room, EndReason.ALL_GUESSED);
        }
    }

    // =====================================================================
    // Scheduling helpers
    // =====================================================================

    private ScheduledFuture<?> schedule(Room room, long turnId, long delayMs, Runnable action) {
        return scheduler.schedule(() -> runForTurn(room, turnId, action),
                delayMs, TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture<?> scheduleRepeating(Room room, long turnId,
                                                 long initialMs, long periodMs, Runnable action) {
        return scheduler.scheduleAtFixedRate(() -> runForTurn(room, turnId, action),
                initialMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs a timer callback under the room lock, but only if the room is still
     * on the turn that scheduled it.
     */
    private void runForTurn(Room room, long turnId, Runnable action) {
        room.lock();
        try {
            if (room.getGame().getTurnId() != turnId) {
                return;   // stale: the game has moved on since this was scheduled
            }
            action.run();
        } catch (RuntimeException e) {
            // Never let this escape: a fixed-rate task that throws is silently
            // cancelled forever, and the countdown would just freeze.
            LOG.log(System.Logger.Level.ERROR, "Timer callback failed in room " + room.getCode(), e);
        } finally {
            room.unlock();
        }
    }

    private Map<String, Integer> scoreSnapshot(Room room) {
        Map<String, Integer> scores = new HashMap<>();
        for (Player p : room.getPlayers()) {
            scores.put(p.getId(), p.getScore());
        }
        return scores;
    }

    private String removalKey(Room room, String playerId) {
        return room.getCode() + ":" + playerId;
    }
}
