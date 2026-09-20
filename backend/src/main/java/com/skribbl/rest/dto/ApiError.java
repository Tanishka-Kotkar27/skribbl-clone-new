package com.skribbl.rest.dto;

import java.util.Map;

/**
 * Uniform error body, so the React client has one error shape to handle.
 *
 * @param error   short machine-readable code
 * @param message human-readable explanation
 * @param fields  per-field validation failures, empty when not applicable
 */
public record ApiError(String error, String message, Map<String, String> fields) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Map.of());
    }
}
