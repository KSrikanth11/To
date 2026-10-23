package com.example.recipient.voice.command.model;

import java.util.ArrayList;
import java.util.List;

/** Bounded user-query history scoped to one compose interaction. */
public record VoiceCommandContext(List<String> userQueries) {
    private static final int MAX_QUERIES = 8;
    private static final int MAX_QUERY_LENGTH = 500;

    public VoiceCommandContext {
        if (userQueries == null) {
            userQueries = List.of();
        } else {
            List<String> cleaned = userQueries.stream()
                    .filter(query -> query != null && !query.isBlank())
                    .map(String::trim)
                    .map(query -> query.length() > MAX_QUERY_LENGTH
                            ? query.substring(0, MAX_QUERY_LENGTH) : query)
                    .toList();
            int firstRecentQuery = Math.max(0, cleaned.size() - MAX_QUERIES);
            userQueries = List.copyOf(cleaned.subList(firstRecentQuery, cleaned.size()));
        }
    }

    public static VoiceCommandContext empty() {
        return new VoiceCommandContext(List.of());
    }

    public VoiceCommandContext append(String query) {
        if (query == null || query.isBlank()) return this;
        List<String> updated = new ArrayList<>(userQueries);
        updated.add(query.trim());
        return new VoiceCommandContext(updated);
    }
}
