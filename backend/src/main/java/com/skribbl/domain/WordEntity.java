package com.skribbl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * The {@code words} table: the pool the drawer's choices are drawn from.
 *
 * <p>Words live in the database rather than a constant in the code so the pool
 * can be extended, filtered by category, and — for the "custom word list" bonus
 * — merged with host-supplied words without a redeploy.
 *
 * <p>{@code difficulty} is stored but not yet used. Phase 3 can weight selection
 * so early rounds get easy words; if that gets cut for time, the column costs
 * nothing.
 */
@Entity
@Table(
        name = "words",
        indexes = {
                @Index(name = "idx_words_text", columnList = "text", unique = true),
                @Index(name = "idx_words_category", columnList = "category")
        }
)
public class WordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "text", nullable = false, unique = true, length = 100)
    private String text;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    /** 1 easy, 2 medium, 3 hard. */
    @Column(name = "difficulty", nullable = false)
    private int difficulty = 1;

    @Column(name = "language", nullable = false, length = 5)
    private String language = "en";

    protected WordEntity() {
        // Required by JPA.
    }

    public WordEntity(String text, String category, int difficulty) {
        this.text = text;
        this.category = category;
        this.difficulty = difficulty;
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(int difficulty) {
        this.difficulty = difficulty;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public String toString() {
        return "WordEntity{" + text + " (" + category + ")}";
    }
}
