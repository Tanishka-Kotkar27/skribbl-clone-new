package com.skribbl;

import com.skribbl.game.WordSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the word-choice algorithm.
 *
 * <p>The {@link Random} is seeded so results are reproducible — a shuffling
 * algorithm tested with an unseeded random is a test that fails once a month for
 * no discoverable reason.
 */
class WordSelectionTest {

    private static final List<String> POOL = List.of(
            "dog", "cat", "pizza", "rocket", "castle", "guitar", "penguin", "umbrella");

    private Random seeded() {
        return new Random(42);
    }

    @Nested
    @DisplayName("Basic selection")
    class Basics {

        @Test
        @DisplayName("returns exactly the requested number of distinct pool words")
        void returnsRequestedCount() {
            List<String> chosen = WordSelection.pick(POOL, List.of(), 3, Set.of(), seeded());

            assertEquals(3, chosen.size());
            assertEquals(3, new HashSet<>(chosen).size(), "choices must be distinct");
            assertTrue(POOL.containsAll(chosen));
        }

        @Test
        @DisplayName("a request for zero still returns one, so a round can start")
        void zeroBecomesOne() {
            assertEquals(1, WordSelection.pick(POOL, List.of(), 0, Set.of(), seeded()).size());
        }

        @Test
        @DisplayName("a pool smaller than the request returns what exists")
        void smallPool() {
            List<String> chosen =
                    WordSelection.pick(List.of("only"), List.of(), 5, Set.of(), seeded());
            assertEquals(List.of("only"), chosen);
        }

        @Test
        @DisplayName("shuffling actually varies the result")
        void selectionVaries() {
            Set<String> firsts = new HashSet<>();
            for (int seed = 0; seed < 60; seed++) {
                firsts.add(WordSelection.pick(POOL, List.of(), 1, Set.of(), new Random(seed)).get(0));
            }
            assertTrue(firsts.size() > 3, "expected varied picks, got " + firsts);
        }
    }

    @Nested
    @DisplayName("Excluding words already used")
    class Exclusion {

        @Test
        @DisplayName("never offers a word already used this game")
        void excludesUsedWords() {
            Set<String> used = Set.of("dog", "cat", "pizza", "rocket", "castle");
            List<String> chosen = WordSelection.pick(POOL, List.of(), 3, used, seeded());

            assertEquals(3, chosen.size());
            assertTrue(chosen.stream().noneMatch(w -> used.contains(w.toLowerCase())));
        }

        @Test
        @DisplayName("exclusion ignores case")
        void exclusionIsCaseInsensitive() {
            List<String> chosen = WordSelection.pick(
                    List.of("Dog", "Cat", "Pizza"), List.of(), 2, Set.of("dog"), seeded());
            assertTrue(chosen.stream().noneMatch(w -> w.equalsIgnoreCase("dog")));
        }

        @Test
        @DisplayName("when every word is used it repeats rather than stalling the round")
        void repeatsRatherThanStalling() {
            Set<String> allUsed = new HashSet<>();
            POOL.forEach(w -> allUsed.add(w.toLowerCase()));

            List<String> chosen = WordSelection.pick(POOL, List.of(), 3, allUsed, seeded());
            assertEquals(3, chosen.size(), "an empty choice list would deadlock the turn");
        }
    }

    @Nested
    @DisplayName("Host custom words")
    class CustomWords {

        private final List<String> custom = List.of("rangoli", "auto rickshaw");

        @Test
        @DisplayName("custom words are offered ahead of pool words")
        void customWordsWin() {
            List<String> chosen = WordSelection.pick(POOL, custom, 3, Set.of(), seeded());
            assertTrue(chosen.containsAll(custom));
            assertEquals(3, chosen.size());
        }

        @Test
        @DisplayName("with only as many slots as custom words, no pool word appears")
        void customWordsFillAllSlots() {
            List<String> chosen = WordSelection.pick(POOL, custom, 2, Set.of(), seeded());
            assertEquals(new HashSet<>(custom), new HashSet<>(chosen));
        }

        @Test
        @DisplayName("blank, null and duplicate custom entries are cleaned up")
        void messyCustomInputIsCleaned() {
            List<String> messy =
                    new ArrayList<>(Arrays.asList("  rangoli  ", "", null, "rangoli", "   "));

            List<String> chosen = WordSelection.pick(POOL, messy, 3, Set.of(), seeded());

            assertEquals(3, chosen.size());
            assertEquals(3, new HashSet<>(chosen).size());
            assertTrue(chosen.contains("rangoli"), "should be trimmed to 'rangoli'");
            assertFalse(chosen.contains(""));
            assertFalse(chosen.contains(null));
            assertEquals(1, chosen.stream().filter("rangoli"::equals).count());
        }
    }

    @Nested
    @DisplayName("Degenerate input")
    class Degenerate {

        @Test
        @DisplayName("an empty pool returns empty rather than throwing")
        void emptyPool() {
            assertTrue(WordSelection.pick(List.of(), List.of(), 3, Set.of(), seeded()).isEmpty());
        }

        @Test
        @DisplayName("nulls throughout are survivable")
        void nullsAreSurvivable() {
            assertNotNull(WordSelection.pick(null, null, 3, null, seeded()));
        }
    }
}
