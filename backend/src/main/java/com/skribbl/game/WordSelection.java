package com.skribbl.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The word-choice algorithm, as a pure function.
 *
 * <p>Deliberately free of Spring, the repository and the database: it takes a
 * pool of strings and returns a selection. That makes the rules — custom words
 * win, no repeats within a game, never return an empty list — testable directly,
 * without a persistence context or a mock.
 *
 * <p>{@link com.skribbl.service.WordService} supplies the pool from its cache
 * and calls this.
 */
public final class WordSelection {

    private WordSelection() {
    }

    /**
     * Picks the distinct words to offer a drawer.
     *
     * <p>Order of preference:
     * <ol>
     *   <li>Host-supplied custom words, so a host who bothered to add them
     *       actually sees them rather than losing them among 244 defaults.</li>
     *   <li>The seeded pool, shuffled.</li>
     *   <li>If everything has been used, the pool again — repeating a word is
     *       better than handing the drawer an empty list and stalling the round.</li>
     * </ol>
     *
     * @param pool        the available words; may be empty
     * @param customWords host-supplied words; may be empty or contain blanks
     * @param wanted      how many choices to return; forced to at least 1
     * @param exclude     lowercased words already used this game
     * @param random      source of shuffling, injectable so tests are deterministic
     * @return up to {@code wanted} distinct words, fewer only if the pool is smaller
     */
    public static List<String> pick(List<String> pool,
                                    List<String> customWords,
                                    int wanted,
                                    Set<String> exclude,
                                    Random random) {
        int target = Math.max(1, wanted);
        Set<String> skip = exclude == null ? Set.of() : exclude;

        List<String> custom = new ArrayList<>();
        if (customWords != null) {
            for (String word : customWords) {
                String cleaned = word == null ? "" : word.trim();
                if (!cleaned.isEmpty() && !skip.contains(cleaned.toLowerCase())) {
                    custom.add(cleaned);
                }
            }
        }
        Collections.shuffle(custom, random);

        List<String> fromPool = new ArrayList<>();
        if (pool != null) {
            for (String word : pool) {
                if (word != null && !skip.contains(word.toLowerCase())) {
                    fromPool.add(word);
                }
            }
        }
        Collections.shuffle(fromPool, random);

        List<String> candidates = new ArrayList<>(custom);
        candidates.addAll(fromPool);

        // LinkedHashSet keeps the shuffled order while dropping any word a host
        // happened to list twice, or that appears in both custom and pool.
        Set<String> chosen = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (chosen.size() >= target) {
                break;
            }
            chosen.add(candidate);
        }

        if (chosen.isEmpty() && pool != null && !pool.isEmpty()) {
            List<String> fallback = new ArrayList<>(pool);
            Collections.shuffle(fallback, random);
            return new ArrayList<>(fallback.subList(0, Math.min(target, fallback.size())));
        }

        return new ArrayList<>(chosen);
    }
}
