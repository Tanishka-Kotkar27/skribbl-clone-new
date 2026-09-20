package com.skribbl.config;

import com.skribbl.domain.WordEntity;
import com.skribbl.repository.WordRepository;
import com.skribbl.service.WordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads {@code data/words.csv} into the {@code words} table at startup.
 *
 * <p>It is <strong>additive and idempotent</strong>: existing words are read once
 * and anything already present is skipped, so restarting never duplicates rows
 * and adding lines to the CSV then restarting is enough to extend the pool. That
 * matters because {@code ddl-auto=update} means the table survives restarts.
 *
 * <p>A malformed line is logged and skipped rather than aborting the run — a
 * typo in a word list should not stop the application from booting.
 */
@Component
public class WordSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WordSeeder.class);
    private static final String RESOURCE = "data/words.csv";

    private final WordRepository wordRepository;
    private final WordService wordService;

    public WordSeeder(WordRepository wordRepository, WordService wordService) {
        this.wordRepository = wordRepository;
        this.wordService = wordService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String[]> parsed = readSeedFile();
        if (parsed.isEmpty()) {
            log.warn("No seed words found at {} - the word pool will be empty", RESOURCE);
            return;
        }


        // One query for everything already stored, rather than an existsByText
        // per line. At 244 words the difference is 1 query instead of 244.
        Set<String> existing = new HashSet<>();
        for (WordEntity word : wordRepository.findAll()) {
            existing.add(word.getText().toLowerCase());
        }

        List<WordEntity> toInsert = new ArrayList<>();
        for (String[] row : parsed) {
            if (!existing.contains(row[0].toLowerCase())) {
                toInsert.add(new WordEntity(row[0], row[1], Integer.parseInt(row[2])));
            }
        }

        if (toInsert.isEmpty()) {
            log.info("Word pool already seeded ({} words)", existing.size());
            wordService.refresh();
            return;
        }

        wordRepository.saveAll(toInsert);
        log.info("Seeded {} new word(s); pool is now {}", toInsert.size(),
                existing.size() + toInsert.size());

        // The in-memory pool must reflect what was just inserted, and this is
        // the only point at which seeding is known to be finished.
        wordService.refresh();
    }

    private List<String[]> readSeedFile() {
        List<String[]> rows = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.error("Seed file {} is missing from the classpath", RESOURCE);
            return rows;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",");
                if (parts.length != 3) {
                    log.warn("Skipping malformed line {} in {}: {}", lineNumber, RESOURCE, line);
                    continue;
                }
                try {
                    int difficulty = Integer.parseInt(parts[2].trim());
                    rows.add(new String[]{parts[0].trim(), parts[1].trim(), String.valueOf(difficulty)});
                } catch (NumberFormatException e) {
                    log.warn("Skipping line {} with non-numeric difficulty: {}", lineNumber, line);
                }
            }
        } catch (IOException e) {
            log.error("Could not read {}", RESOURCE, e);
        }
        return rows;
    }
}
