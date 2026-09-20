package com.skribbl.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skribbl.game.GameSettings;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores {@link GameSettings} as a JSON string in a single column.
 *
 * <p>The brief asks for a {@code settings JSON} column, and this is the portable
 * way to get one: a plain {@code VARCHAR} plus this converter works identically
 * on MySQL and on the H2 database the tests run against, whereas MySQL's native
 * {@code JSON} type would make the test suite need a real MySQL instance.
 *
 * <p>Settings are read and written as one blob and never queried field by field,
 * so there is nothing to gain from normalising them into their own table.
 */
@Converter
public class GameSettingsConverter implements AttributeConverter<GameSettings, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(GameSettings settings) {
        if (settings == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize GameSettings", e);
        }
    }

    @Override
    public GameSettings convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new GameSettings();
        }
        try {
            return MAPPER.readValue(json, GameSettings.class);
        } catch (JsonProcessingException e) {
            // A settings blob written by an older build should not make the
            // whole room unreadable, so fall back to defaults rather than
            // throwing and losing the row.
            return new GameSettings();
        }
    }
}
