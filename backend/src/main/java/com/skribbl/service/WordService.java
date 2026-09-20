package com.skribbl.service;

import com.skribbl.domain.WordEntity;
import com.skribbl.game.GameSettings;
import com.skribbl.game.WordProvider;
import com.skribbl.game.WordSelection;
import com.skribbl.repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Supplies the word choices offered to each drawer.
 *
 * <p><strong>The pool is cached in memory.</strong> It is a few hundred rows that
 * change only when the seed file changes, and the alternative —
 * {@code ORDER BY RAND() LIMIT n} — costs a full table scan and a sort in the
 * middle of a round, and is not portable to the H2 database the tests use.
 * Loading once at startup makes selection a list shuffle.
 *
 * <p>The cache is a volatile immutable list replaced wholesale on refresh, so
 * readers never see a partially built pool and no lock is needed on the read
 * path.
 *
 * <p>The selection rules themselves live in {@link WordSelection}, which has no
 * Spring or database dependency and is unit-tested directly.
 */
@Service
public class WordService implements WordProvider {

    private static final Logger log = LoggerFactory.getLogger(WordService.class);

    /** Fallback if the table is somehow empty, so a game can still start. */
    private static final List<String> EMERGENCY_WORDS =
            List.of("cat", "house", "tree", "car", "sun", "book", "star", "fish");

    private final WordRepository wordRepository;
    private volatile List<String> cachedWords = List.of();

    public WordService(WordRepository wordRepository) {
        this.wordRepository = wordRepository;
    }

    /**
     * Reloads the pool from the database.
     *
     * <p>Called by {@code WordSeeder} once seeding is complete. Deliberately not
     * {@code @PostConstruct}: bean construction order would not guarantee the
     * seeder had finished, and the cache would come up empty on a fresh database.
     */
    public synchronized void refresh() {
        List<String> words = new ArrayList<>();
        for (WordEntity entity : wordRepository.findAll()) {
            words.add(entity.getText());
        }
        this.cachedWords = List.copyOf(words);
        log.info("Word pool loaded: {} words", words.size());
    }

    /**
     * Picks the distinct word choices to offer a drawer.
     *
     * @param settings supplies the choice count and any host custom words
     * @param exclude  lowercased words already used this game
     */
    public List<String> pickChoices(GameSettings settings, Set<String> exclude) {
        return WordSelection.pick(
                pool(),
                settings.getCustomWords(),
                settings.getWordChoices(),
                exclude,
                ThreadLocalRandom.current());
    }

    /**
     * {@link WordProvider} entry point used by the game engine, which asks for a
     * specific count (twice the usual in combination mode).
     */
    @Override
    public List<String> pick(GameSettings settings, Set<String> exclude, int count) {
        return WordSelection.pick(
                pool(),
                settings.getCustomWords(),
                count,
                exclude,
                ThreadLocalRandom.current());
    }

    /** Convenience overload when nothing needs excluding. */
    public List<String> pickChoices(GameSettings settings) {
        return pickChoices(settings, new HashSet<>());
    }

    public int poolSize() {
        return pool().size();
    }

    public List<String> categories() {
        return wordRepository.findDistinctCategories();
    }

    /** Ensures the pool is loaded before first use. */
    private List<String> pool() {
        List<String> current = cachedWords;
        if (current.isEmpty()) {
            refresh();
            current = cachedWords;
        }
        return current.isEmpty() ? EMERGENCY_WORDS : current;
    }
}
