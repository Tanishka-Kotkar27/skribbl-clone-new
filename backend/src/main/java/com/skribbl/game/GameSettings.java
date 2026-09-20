package com.skribbl.game;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-configurable room settings.
 *
 * <p>The ranges below come straight from the assignment brief and are enforced
 * by Bean Validation, so an out-of-range payload is rejected with HTTP 400 at
 * the controller boundary rather than corrupting a running game. The constants
 * are public so the React client can render the same bounds on its sliders
 * without hard-coding a second copy of the rules.
 */
public class GameSettings {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 20;
    public static final int MIN_ROUNDS = 2;
    public static final int MAX_ROUNDS = 10;
    public static final int MIN_DRAW_TIME = 15;
    public static final int MAX_DRAW_TIME = 240;
    public static final int MIN_WORD_CHOICES = 1;
    public static final int MAX_WORD_CHOICES = 5;
    public static final int MIN_HINTS = 0;
    public static final int MAX_HINTS = 5;

    @Min(value = MIN_PLAYERS, message = "maxPlayers must be at least " + MIN_PLAYERS)
    @Max(value = MAX_PLAYERS, message = "maxPlayers must be at most " + MAX_PLAYERS)
    private int maxPlayers = 8;

    @Min(value = MIN_ROUNDS, message = "rounds must be at least " + MIN_ROUNDS)
    @Max(value = MAX_ROUNDS, message = "rounds must be at most " + MAX_ROUNDS)
    private int rounds = 3;

    @Min(value = MIN_DRAW_TIME, message = "drawTimeSeconds must be at least " + MIN_DRAW_TIME)
    @Max(value = MAX_DRAW_TIME, message = "drawTimeSeconds must be at most " + MAX_DRAW_TIME)
    private int drawTimeSeconds = 80;

    @Min(value = MIN_WORD_CHOICES, message = "wordChoices must be at least " + MIN_WORD_CHOICES)
    @Max(value = MAX_WORD_CHOICES, message = "wordChoices must be at most " + MAX_WORD_CHOICES)
    private int wordChoices = 3;

    @Min(value = MIN_HINTS, message = "hints must be at least " + MIN_HINTS)
    @Max(value = MAX_HINTS, message = "hints must be at most " + MAX_HINTS)
    private int hints = 2;

    @NotNull
    private WordMode wordMode = WordMode.NORMAL;

    /** Private rooms are join-by-code only and are not listed publicly. */
    private boolean isPrivate = true;

    /** Optional host-supplied words, mixed into the pool drawn from the DB. */
    private List<String> customWords = new ArrayList<>();

    public GameSettings() {
        // Defaults above are used when the client omits fields.
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getRounds() {
        return rounds;
    }

    public void setRounds(int rounds) {
        this.rounds = rounds;
    }

    public int getDrawTimeSeconds() {
        return drawTimeSeconds;
    }

    public void setDrawTimeSeconds(int drawTimeSeconds) {
        this.drawTimeSeconds = drawTimeSeconds;
    }

    public int getWordChoices() {
        return wordChoices;
    }

    public void setWordChoices(int wordChoices) {
        this.wordChoices = wordChoices;
    }

    public int getHints() {
        return hints;
    }

    public void setHints(int hints) {
        this.hints = hints;
    }

    public WordMode getWordMode() {
        return wordMode;
    }

    public void setWordMode(WordMode wordMode) {
        this.wordMode = wordMode;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean aPrivate) {
        this.isPrivate = aPrivate;
    }

    public List<String> getCustomWords() {
        return customWords;
    }

    public void setCustomWords(List<String> customWords) {
        this.customWords = customWords == null ? new ArrayList<>() : customWords;
    }

    @Override
    public String toString() {
        return "GameSettings{maxPlayers=" + maxPlayers
                + ", rounds=" + rounds
                + ", drawTimeSeconds=" + drawTimeSeconds
                + ", wordChoices=" + wordChoices
                + ", hints=" + hints
                + ", wordMode=" + wordMode
                + ", isPrivate=" + isPrivate + '}';
    }
}
