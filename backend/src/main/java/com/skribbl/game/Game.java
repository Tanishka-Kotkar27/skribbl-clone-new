package com.skribbl.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * The round/turn/scoring state machine for one {@link Room}.
 *
 * <p>This class is the heart of the application and is written to be
 * <strong>independent of transport</strong>: nothing here knows about STOMP,
 * sockets or HTTP. It is driven by {@code GameEngineService} (Phase 3), which
 * owns the scheduler and does the broadcasting. That separation is what makes
 * turn rotation, word masking and scoring unit-testable without a running
 * server.
 *
 * <p><strong>Round vocabulary.</strong> Following skribbl.io: a <em>round</em> is one
 * full cycle in which every player draws once. So a 3-round game with 4 players
 * is 12 drawing turns. {@code currentRound} counts cycles; {@code turnIndex}
 * counts position within the current cycle.
 *
 * <p>All methods assume the caller already holds the owning room's lock.
 */
public class Game {

    /** Points a correct guess is worth at the very start of the timer. */
    private static final int MAX_SPEED_POINTS = 200;
    /** Extra points for guessing before others; decays by guess order. */
    private static final int ORDER_BONUS_BASE = 50;
    private static final int ORDER_BONUS_STEP = 10;
    /** Floor, so a last-second correct guess is still worth something. */
    private static final int MIN_GUESS_POINTS = 25;
    /** What the drawer earns per player who guesses their word. */
    private static final int DRAWER_POINTS_PER_GUESSER = 25;

    private final Room room;

    private GamePhase phase = GamePhase.LOBBY;

    /** 1-based cycle counter, up to {@code settings.getRounds()}. */
    private int currentRound = 0;

    /** Position within the current cycle, 0-based. */
    private int turnIndex = 0;

    /** Player ids in drawing order, frozen when the game starts. */
    private final List<String> turnOrder = new ArrayList<>();

    private String drawerId;

    /** The word being drawn. Never sent to guessers in clear text. */
    private String currentWord;

    /** The N options offered to the drawer during {@link GamePhase#CHOOSING}. */
    private List<String> wordChoices = new ArrayList<>();

    private Instant roundStartedAt;
    private Instant roundEndsAt;

    /** Indices of letters already revealed as hints. */
    private final Set<Integer> revealedIndices = new HashSet<>();

    /** Ids of players who have guessed correctly this round, in guess order. */
    private final Set<String> correctGuessers = new LinkedHashSet<>();

    /** Words already drawn this game, lowercased, so choices never repeat. */
    private final Set<String> usedWords = new HashSet<>();

    /** Increments every turn; see {@link TurnRecord} for why this exists. */
    private long turnCounter = 0;

    /** The turn currently in progress, or null in the lobby. */
    private TurnRecord currentTurn;

    /**
     * Set when the active drawer leaves mid-turn. Removing them slides the next
     * player into their slot, so the following {@link #advanceTurn()} must not
     * increment the index again — otherwise that player's turn is skipped.
     */
    private boolean drawerSlotVacated;

    /** Guards against announcing the winner twice (e.g. a timer and a disconnect racing). */
    private boolean resultsAnnounced;

    /** Handles to in-flight timers so they can be cancelled on early round end. */
    private transient ScheduledFuture<?> roundTimerFuture;
    private transient ScheduledFuture<?> hintTimerFuture;
    private transient ScheduledFuture<?> tickFuture;
    private transient ScheduledFuture<?> choiceTimerFuture;
    private transient ScheduledFuture<?> nextTurnFuture;

    public Game(Room room) {
        this.room = room;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Freezes the turn order and moves out of the lobby. The caller is
     * responsible for then invoking {@link #beginTurn()} and broadcasting.
     */
    public void start() {
        turnOrder.clear();
        for (Player p : room.getConnectedPlayers()) {
            turnOrder.add(p.getId());
            p.setScore(0);
        }
        this.currentRound = 1;
        this.turnIndex = 0;
        this.usedWords.clear();
        this.drawerSlotVacated = false;
        this.resultsAnnounced = false;
        this.currentTurn = null;
        this.phase = GamePhase.CHOOSING;
        room.setStatus(RoomStatus.IN_PROGRESS);
    }

    /**
     * Prepares the next drawing turn: picks the drawer, clears per-round state
     * and moves to {@link GamePhase#CHOOSING}. Word choices are injected
     * separately by the engine, which owns the word repository.
     */
    public void beginTurn() {
        if (turnOrder.isEmpty()) {
            return;
        }
        this.drawerId = turnOrder.get(turnIndex % turnOrder.size());
        this.turnCounter++;
        this.currentTurn = new TurnRecord(turnCounter, drawerId, currentRound, turnIndex);
        this.currentWord = null;
        this.wordChoices = new ArrayList<>();
        this.revealedIndices.clear();
        this.correctGuessers.clear();
        this.roundStartedAt = null;
        this.roundEndsAt = null;
        for (Player p : room.playerView()) {
            p.setGuessedCurrentRound(false);
            p.resetRoundScore();
        }
        room.clearCanvas();
        this.phase = GamePhase.CHOOSING;
    }

    /**
     * Locks in the drawer's word and starts the clock.
     */
    public void confirmWord(String word) {
        this.currentWord = word;
        if (word != null) {
            usedWords.add(word.toLowerCase(Locale.ROOT));
        }
        this.roundStartedAt = Instant.now();
        this.roundEndsAt = roundStartedAt.plusSeconds(room.getSettings().getDrawTimeSeconds());
        this.phase = GamePhase.DRAWING;
    }

    /**
     * Advances past the current turn.
     *
     * @return {@code true} if the whole game is now over
     */
    public boolean advanceTurn() {
        this.phase = GamePhase.ROUND_END;
        if (drawerSlotVacated) {
            // The departed drawer's successor already occupies this index.
            drawerSlotVacated = false;
        } else {
            turnIndex++;
        }
        return normaliseTurnPosition();
    }

    /**
     * Wraps the turn index into the next cycle when it runs off the end of the
     * rotation, and detects the end of the game.
     *
     * <p>Called by {@link #advanceTurn()}, and again by the engine just before
     * the next turn starts — a player may have left during the round-end pause
     * and shortened the rotation underneath the index.
     *
     * @return {@code true} if every round has now been played
     */
    public boolean normaliseTurnPosition() {
        if (!turnOrder.isEmpty() && turnIndex >= turnOrder.size()) {
            turnIndex = 0;
            currentRound++;
        }
        if (turnOrder.isEmpty() || currentRound > room.getSettings().getRounds()) {
            finish();
            return true;
        }
        return false;
    }

    /** Ends the game immediately, e.g. when too few players remain. */
    public void finish() {
        this.phase = GamePhase.GAME_OVER;
        room.setStatus(RoomStatus.FINISHED);
    }

    /**
     * Removes a player from the rotation when they disconnect, keeping
     * {@code turnIndex} pointing at the same upcoming player.
     */
    public void removeFromRotation(String playerId) {
        int idx = turnOrder.indexOf(playerId);
        if (idx < 0) {
            return;
        }
        boolean isActiveDrawer = playerId.equals(drawerId)
                && (phase == GamePhase.CHOOSING || phase == GamePhase.DRAWING);
        turnOrder.remove(idx);

        if (idx < turnIndex) {
            // Someone earlier in the order left: shift back so the index still
            // points at the same upcoming player.
            turnIndex--;
        } else if (idx == turnIndex && isActiveDrawer) {
            drawerSlotVacated = true;
        }
        // Running off the end is handled by normaliseTurnPosition(), which also
        // counts it as a completed cycle.
    }

    /** True while a game is being played (not lobby, not finished). */
    public boolean isInProgress() {
        return phase == GamePhase.CHOOSING
                || phase == GamePhase.DRAWING
                || phase == GamePhase.ROUND_END;
    }

    // --------------------------------------------------------------- scoring

    /**
     * Records a correct guess and returns the points awarded.
     *
     * <p>Points combine <em>speed</em> (how much of the timer is left) with
     * <em>order</em> (a decaying bonus for being early), which is what makes a
     * skribbl round feel like a race rather than a lottery.
     *
     * @return points awarded, or 0 if this player had already guessed
     */
    public int registerCorrectGuess(Player player) {
        if (player == null || correctGuessers.contains(player.getId())) {
            return 0;
        }
        correctGuessers.add(player.getId());
        player.setGuessedCurrentRound(true);

        int points = Math.max(MIN_GUESS_POINTS, speedPoints() + orderBonus(correctGuessers.size()));
        player.addScore(points);
        return points;
    }

    private int speedPoints() {
        if (roundEndsAt == null || roundStartedAt == null) {
            return MIN_GUESS_POINTS;
        }
        long total = room.getSettings().getDrawTimeSeconds();
        long remaining = Math.max(0, roundEndsAt.getEpochSecond() - Instant.now().getEpochSecond());
        if (total <= 0) {
            return MIN_GUESS_POINTS;
        }
        double fraction = (double) remaining / (double) total;
        return (int) Math.round(MAX_SPEED_POINTS * fraction);
    }

    private int orderBonus(int order) {
        return Math.max(0, ORDER_BONUS_BASE - (ORDER_BONUS_STEP * (order - 1)));
    }

    /**
     * Awards the drawer for how many players managed to guess their word, which
     * discourages both unguessable scribbles and giving the word away.
     *
     * @return points awarded to the drawer
     */
    public int awardDrawer() {
        Optional<Player> drawer = room.getPlayer(drawerId);
        if (drawer.isEmpty()) {
            return 0;
        }
        int points = correctGuessers.size() * DRAWER_POINTS_PER_GUESSER;
        drawer.get().addScore(points);
        return points;
    }

    /** True once every non-drawing player has guessed, so the round can end early. */
    public boolean allGuessersCorrect() {
        int eligible = 0;
        for (Player p : room.getConnectedPlayers()) {
            if (!p.getId().equals(drawerId)) {
                eligible++;
            }
        }
        return eligible > 0 && correctGuessers.size() >= eligible;
    }

    /** Players sorted by score, highest first. */
    public List<Player> getLeaderboard() {
        List<Player> sorted = room.getPlayers();
        sorted.sort(Comparator.comparingInt(Player::getScore).reversed());
        return sorted;
    }

    public Optional<Player> getWinner() {
        List<Player> board = getLeaderboard();
        return board.isEmpty() ? Optional.empty() : Optional.of(board.get(0));
    }

    // ------------------------------------------------------- word and hints

    /**
     * The word as guessers should see it: underscores for hidden letters,
     * spaces and hyphens always visible, revealed hint letters filled in.
     *
     * <p>Example: {@code "ice cream"} with the {@code c} revealed renders as
     * {@code "_ _ _   c _ _ _ _"}.
     */
    public String getMaskedWord() {
        if (currentWord == null) {
            return "";
        }
        if (room.getSettings().getWordMode() == WordMode.HIDDEN) {
            // Hidden mode: guessers get no blanks and no length to work from.
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < currentWord.length(); i++) {
            char c = currentWord.charAt(i);
            if (c == ' ' || c == '-') {
                sb.append(c);
            } else if (revealedIndices.contains(i)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Reveals one more letter, chosen at random from the still-hidden ones.
     * Never reveals so much that the word becomes trivially readable: stops at
     * half the letters regardless of the configured hint count.
     *
     * @return the index revealed, or empty if nothing further may be revealed
     */
    public Optional<Integer> revealNextHint() {
        if (currentWord == null) {
            return Optional.empty();
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < currentWord.length(); i++) {
            char c = currentWord.charAt(i);
            if (c != ' ' && c != '-' && !revealedIndices.contains(i)) {
                candidates.add(i);
            }
        }
        int letterCount = 0;
        for (int i = 0; i < currentWord.length(); i++) {
            char c = currentWord.charAt(i);
            if (c != ' ' && c != '-') {
                letterCount++;
            }
        }
        if (candidates.isEmpty() || revealedIndices.size() >= letterCount / 2) {
            return Optional.empty();
        }
        int pick = candidates.get((int) (Math.random() * candidates.size()));
        revealedIndices.add(pick);
        return Optional.of(pick);
    }

    /**
     * Normalises a guess or word for comparison: trims, lowercases, collapses
     * runs of internal whitespace and drops punctuation.
     *
     * <p>Used on both sides of the comparison so {@code " Ice  Cream! "} matches
     * {@code "ice cream"}.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String lowered = raw.trim().toLowerCase(Locale.ROOT);
        String stripped = lowered.replaceAll("[^a-z0-9\\s-]", "");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    /** True if {@code guess} matches the current word after normalisation. */
    public boolean isCorrectGuess(String guess) {
        if (currentWord == null) {
            return false;
        }
        String g = normalise(guess);
        String w = normalise(currentWord);
        if (g.isEmpty()) {
            return false;
        }
        // "icecream" and "ice-cream" both count for "ice cream": spacing and
        // hyphens are not what the player is being tested on.
        return g.equals(w) || squash(g).equals(squash(w));
    }

    /**
     * True if a chat message contains the word, so it must not be shown to
     * players who are still guessing.
     *
     * <p>Without this, typing "is it rocket?" would be a wrong guess (it is not
     * exactly the word) and be broadcast to everyone — handing them the answer.
     *
     * @param strict also catch the word spelled out with gaps ("r o c k e t").
     *               Used for the drawer, who has every reason to try it; not
     *               for guessers, where it would block innocent messages
     *               ("I want…" contains "ant").
     */
    public boolean mentionsWord(String text, boolean strict) {
        if (currentWord == null) {
            return false;
        }
        String t = normalise(text);
        String w = normalise(currentWord);
        if (t.isEmpty() || w.isEmpty()) {
            return false;
        }
        if ((" " + t + " ").contains(" " + w + " ")) {
            return true;
        }
        boolean multiWord = w.contains(" ") || w.contains("-");
        if (strict || multiWord) {
            String sw = squash(w);
            return sw.length() >= 3 && squash(t).contains(sw);
        }
        return false;
    }

    private static String squash(String s) {
        return s.replace(" ", "").replace("-", "");
    }

    /**
     * True when a guess is one edit away from the word, so the UI can say
     * "close!" without awarding points. This is the small touch that makes the
     * clone feel like the original.
     */
    public boolean isCloseGuess(String guess) {
        if (currentWord == null) {
            return false;
        }
        String a = normalise(guess);
        String b = normalise(currentWord);
        if (a.isEmpty() || a.equals(b)) {
            return false;
        }
        return levenshtein(a, b) == 1;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------- accessors

    public Room getRoom() {
        return room;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public List<String> getTurnOrder() {
        return new ArrayList<>(turnOrder);
    }

    public String getDrawerId() {
        return drawerId;
    }

    public String getCurrentWord() {
        return currentWord;
    }

    public List<String> getWordChoices() {
        return new ArrayList<>(wordChoices);
    }

    public void setWordChoices(List<String> wordChoices) {
        this.wordChoices = wordChoices == null ? new ArrayList<>() : new ArrayList<>(wordChoices);
    }

    public Instant getRoundStartedAt() {
        return roundStartedAt;
    }

    public Instant getRoundEndsAt() {
        return roundEndsAt;
    }

    /** Seconds left on the clock, floored at zero. */
    public int getRemainingSeconds() {
        if (roundEndsAt == null) {
            return 0;
        }
        long millis = roundEndsAt.toEpochMilli() - Instant.now().toEpochMilli();
        // Round up, so the display reads 80, 79 ... 1 and only shows 0 at the end.
        return (int) Math.max(0, (millis + 999) / 1000);
    }

    public Set<String> getCorrectGuessers() {
        return new LinkedHashSet<>(correctGuessers);
    }

    public Set<Integer> getRevealedIndices() {
        return new HashSet<>(revealedIndices);
    }

    public Set<String> getUsedWords() {
        return new HashSet<>(usedWords);
    }

    /**
     * Claims the right to announce the final results.
     *
     * @return {@code true} exactly once per game
     */
    public boolean markResultsAnnounced() {
        if (resultsAnnounced) {
            return false;
        }
        resultsAnnounced = true;
        return true;
    }

    public TurnRecord getCurrentTurn() {
        return currentTurn;
    }

    /** Id of the turn in progress; 0 before the first turn. */
    public long getTurnId() {
        return currentTurn == null ? 0 : currentTurn.getTurnId();
    }

    public ScheduledFuture<?> getChoiceTimerFuture() {
        return choiceTimerFuture;
    }

    public void setChoiceTimerFuture(ScheduledFuture<?> choiceTimerFuture) {
        this.choiceTimerFuture = choiceTimerFuture;
    }

    public ScheduledFuture<?> getNextTurnFuture() {
        return nextTurnFuture;
    }

    public void setNextTurnFuture(ScheduledFuture<?> nextTurnFuture) {
        this.nextTurnFuture = nextTurnFuture;
    }

    public ScheduledFuture<?> getRoundTimerFuture() {
        return roundTimerFuture;
    }

    public void setRoundTimerFuture(ScheduledFuture<?> roundTimerFuture) {
        this.roundTimerFuture = roundTimerFuture;
    }

    public ScheduledFuture<?> getHintTimerFuture() {
        return hintTimerFuture;
    }

    public void setHintTimerFuture(ScheduledFuture<?> hintTimerFuture) {
        this.hintTimerFuture = hintTimerFuture;
    }

    public ScheduledFuture<?> getTickFuture() {
        return tickFuture;
    }

    public void setTickFuture(ScheduledFuture<?> tickFuture) {
        this.tickFuture = tickFuture;
    }

    /**
     * Cancels every in-flight timer for this room. Must be called before
     * starting a new turn, otherwise the previous turn's timer will fire during
     * the next one and end it early — the single most common bug in this kind
     * of game loop.
     */
    public void cancelTimers() {
        if (roundTimerFuture != null) {
            roundTimerFuture.cancel(false);
            roundTimerFuture = null;
        }
        if (hintTimerFuture != null) {
            hintTimerFuture.cancel(false);
            hintTimerFuture = null;
        }
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        if (choiceTimerFuture != null) {
            choiceTimerFuture.cancel(false);
            choiceTimerFuture = null;
        }
        if (nextTurnFuture != null) {
            nextTurnFuture.cancel(false);
            nextTurnFuture = null;
        }
    }
}
