package com.skribbl.repository;

import com.skribbl.domain.WordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WordRepository extends JpaRepository<WordEntity, Long> {

    boolean existsByText(String text);

    List<WordEntity> findByCategory(String category);

    List<WordEntity> findByDifficulty(int difficulty);

    /** Distinct categories, for a future category picker in room settings. */
    @Query("SELECT DISTINCT w.category FROM WordEntity w ORDER BY w.category")
    List<String> findDistinctCategories();

    /**
     * Note there is no {@code ORDER BY RAND()} query here on purpose.
     *
     * <p>{@code ORDER BY RAND()} forces a full table scan and a sort on every
     * call, and it is not portable between MySQL and the H2 database the tests
     * use. The pool is a few hundred rows, so {@code WordService} loads it once
     * at startup and picks from memory instead — no database round-trip in the
     * middle of a round, and the selection logic becomes unit-testable.
     */
    long count();
}
