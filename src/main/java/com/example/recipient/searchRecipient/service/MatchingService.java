package com.example.recipient.searchRecipient.service;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.RecipientIndexRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MatchingService {
    private final double minScore;
    public MatchingService(@Value("${resolver.min-score:0.58}") double minScore) { this.minScore = minScore; }

    public record Ranked(List<String> terms, String type, String reason, double score) { }
    public Ranked rank(String text, Intent intent, boolean subgroup, String description) {
        List<String> recordTokens = AliasService.tokens(text + " " + (description == null ? "" : description));
        return rankTokens(recordTokens, text, intent, subgroup, description);
    }
    public Ranked rank(RecipientIndexRecord record, Intent intent) {
        boolean subgroup = "subgroup".equals(record.type());
        List<String> recordTokens = new ArrayList<>();
        recordTokens.addAll(record.tokens());
        recordTokens.addAll(record.source_tokens());
        recordTokens.addAll(AliasService.tokens(record.search_text()));
        recordTokens.addAll(AliasService.tokens(record.name()));
        recordTokens.addAll(AliasService.tokens(record.username()));
        recordTokens.addAll(AliasService.tokens(record.description()));
        return rankTokens(recordTokens, subgroup ? record.name() : record.username(), intent,
                subgroup, subgroup ? record.description() : null);
    }
    private Ranked rankTokens(List<String> recordTokens, String text, Intent intent, boolean subgroup, String description) {
        List<String> normalizedRecordTokens = recordTokens.stream()
                .flatMap(token -> AliasService.tokens(token).stream()).distinct().toList();
        List<String> matched = new ArrayList<>();
        boolean usedFuzzy = false;
        for (Intent.Concept concept : intent.concepts()) {
            boolean exact = concept.alternatives().stream().anyMatch(a -> phraseMatches(normalizedRecordTokens, a));
            if (exact) matched.add(concept.label());
            else if (concept.alternatives().stream().anyMatch(a -> fuzzyPhraseMatches(normalizedRecordTokens, a))) {
                matched.add(concept.label()); usedFuzzy = true;
            }
        }
        if (intent.concepts().isEmpty()) return null;
        double coverage = (double) matched.size() / intent.concepts().size();
        if (coverage == 0) return null;
        double score = 0.36 + 0.62 * coverage;
        if (usedFuzzy) score = Math.min(score, 0.84);
        String normalizedQuery = AliasService.normalize(intent.originalQuery());
        boolean exact = AliasService.normalize(text).equals(normalizedQuery);
        if (exact && coverage == 1.0 && !usedFuzzy) score = 1.0;
        else if (coverage == 1.0 && !usedFuzzy && subgroup && description != null && !description.isBlank()) score = Math.min(0.99, score + 0.015);
        score = Math.round(score * 100.0) / 100.0;
        if (score <= minScore) return null;
        String type = coverage < 1.0 || usedFuzzy ? "POSSIBLE_MATCH" : exact ? "EXACT_MATCH" : "TOKEN_MATCH";
        String reason = usedFuzzy ? "Matched with fuzzy token similarity; verify before use"
                : coverage == 1.0
                ? "Matched all extracted query concepts: " + String.join(", ", matched)
                : "Matched " + matched.size() + " of " + intent.concepts().size() + " extracted query concepts";
        return new Ranked(List.copyOf(matched), type, reason, score);
    }
    private static boolean phraseMatches(List<String> recordTokens, String alternative) {
        List<String> wanted = AliasService.tokens(alternative);
        if (wanted.isEmpty()) return false;
        // All phrase tokens must occur; this handles hyphens, underscores, and word-order changes.
        return recordTokens.containsAll(wanted);
    }
    private static boolean fuzzyPhraseMatches(List<String> recordTokens, String alternative) {
        List<String> wanted = AliasService.tokens(alternative);
        if (wanted.isEmpty()) return false;
        for (String token : wanted) {
            if (recordTokens.contains(token)) continue;
            if (token.length() < 5 || recordTokens.stream().noneMatch(candidate -> similarity(token, candidate) >= 0.80)) return false;
        }
        return true;
    }
    private static double similarity(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length());
    }
}
