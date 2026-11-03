package com.example.recipient.searchRecipient.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;


import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;

/**
 * Field-aware matching. Every filter in the query must hold on the matching field; there is no partial
 * coverage. A match is CONFIRMED only when every filter holds through exact, verified evidence; anything
 * weaker (fuzzy word, unverified code, group with extra designations) is SUGGESTED and never auto-selected.
 */
public class RecipientMatcher {
    public static final String CONFIRMED = "CONFIRMED";
    public static final String SUGGESTED = "SUGGESTED";

    public record Ranked(String status, String type, List<String> terms, String reason, double score) {
        public boolean confirmed() { return CONFIRMED.equals(status); }
    }

    private final RecipientCatalog catalog;

    public RecipientMatcher(RecipientCatalog catalog) { this.catalog = catalog; }

    /**
     * Checks each keyword against the directory's vocabulary. A missing word is first read as a
     * misheard designation or location, then given near spellings; either way it is reported back as
     * unresolved so the caller asks instead of silently dropping the word.
     */
    public RecipientQuery prepare(RecipientQuery query, Set<String> vocabulary) {
        return prepare(query, Vocabulary.of(vocabulary));
    }

    public RecipientQuery prepare(RecipientQuery query, Vocabulary vocabulary) {
        Set<String> designations = new LinkedHashSet<>(query.designations());
        Set<String> locations = new LinkedHashSet<>(query.locations());
        Set<String> inferred = new LinkedHashSet<>(query.inferred());
        List<RecipientQuery.Keyword> keywords = new ArrayList<>();
        List<RecipientQuery.UnresolvedTerm> unresolved = new ArrayList<>(query.unresolved());
        for (RecipientQuery.Keyword keyword : query.keywords()) {
            String term = keyword.term();
            if (vocabulary.known(term)) { keywords.add(keyword); continue; }
            String singular = term.endsWith("s") && term.length() > 3 ? term.substring(0, term.length() - 1) : null;
            if (singular != null && vocabulary.known(singular)) { keywords.add(new RecipientQuery.Keyword(singular)); continue; }

            Optional<RecipientCatalog.Entry> near = catalog.nearest(term);
            if (near.isPresent()) {
                RecipientCatalog.Entry entry = near.get();
                (entry.kind() == RecipientCatalog.Kind.DESIGNATION ? designations : locations).add(entry.key());
                inferred.add(entry.key());
                unresolved.add(new RecipientQuery.UnresolvedTerm(term, List.of(catalog.label(entry.key()))));
                continue;
            }
            Set<String> fuzzy = term.length() >= 5 ? vocabulary.similar(term) : Set.of();
            keywords.add(new RecipientQuery.Keyword(term, Set.copyOf(fuzzy)));
            unresolved.add(new RecipientQuery.UnresolvedTerm(term, List.copyOf(fuzzy)));
        }
        return new RecipientQuery(Set.copyOf(designations), Set.copyOf(locations), catalog.expand(locations),
                List.copyOf(keywords), query.excludedDesignations(), query.excludedLocations(), query.excludedKeywords(),
                Set.copyOf(inferred), List.copyOf(unresolved), query.allScope(), query.recipientType(), query.exactKey());
    }

    /** The words a list's records hold, asked only about the terms a query leaves unread. */
    public interface Vocabulary {
        /** Whether some record holds this word, or (four letters or more) a word starting with it. */
        boolean known(String term);

        /** Up to five held words of four or more letters within similarity 0.80, sorted. */
        Set<String> similar(String term);

        static Vocabulary of(Set<String> words) {
            return new Vocabulary() {
                public boolean known(String term) {
                    if (words.contains(term)) return true;
                    if (term.length() < 4) return false;
                    if (words instanceof NavigableSet<String> sorted) {
                        String next = sorted.higher(term);
                        return next != null && next.startsWith(term);
                    }
                    return words.stream().anyMatch(token -> token.startsWith(term));
                }

                public Set<String> similar(String term) {
                    Set<String> out = new LinkedHashSet<>();
                    words.stream().filter(token -> isSimilar(term, token)).sorted().limit(5).forEach(out::add);
                    return out;
                }
            };
        }

        static boolean isSimilar(String term, String token) {
            // similarity >= 0.80 allows at most max(length) / 5 edits, so longer gaps can be skipped unscored
            return token.length() >= 4
                    && Math.abs(token.length() - term.length()) <= Math.max(token.length(), term.length()) / 5
                    && TextNormalizer.similarity(term, token) >= 0.80;
        }
    }

    public Ranked match(ParsedRecipient record, RecipientQuery query) {
        if (!query.exactKey().isEmpty() && query.exactKey().equals(record.exactKey()))
            return new Ranked(CONFIRMED, "EXACT_MATCH", List.of(record.name()), "Exact name match", 1.0);
        if (!query.hasFilters() || excluded(record, query)) return null;

        List<String> terms = new ArrayList<>();
        List<String> doubts = new ArrayList<>();

        if (!query.designations().isEmpty()) {
            Set<String> hit = intersection(record.designations().keySet(), query.designations());
            if (hit.isEmpty()) return null;
            hit.forEach(code -> terms.add(catalog.label(code)));
            if (hit.stream().noneMatch(record.designations()::get)) doubts.add("designation is not certain for this account");
            if (query.inferred().containsAll(hit)) doubts.add("designation was inferred from a misheard word");
            if (record.isSubgroup()) {
                // A narrower rank of a requested one (WSI for SI) is not an extra designation.
                Set<String> extra = new LinkedHashSet<>(record.designations().keySet());
                extra.removeIf(code -> catalog.withParents(code).stream().anyMatch(query.designations()::contains));
                if (!extra.isEmpty()) doubts.add("group also includes " + labels(extra));
            }
        }

        if (!query.locations().isEmpty()) {
            boolean anyVerified = false;
            boolean anyHit = false;
            for (Map.Entry<String, Boolean> location : record.locations().entrySet()) {
                if (!query.expandedLocations().contains(location.getKey())) continue;
                anyHit = true;
                anyVerified |= location.getValue();
                terms.add(query.locations().contains(location.getKey()) ? location.getKey()
                        : location.getKey() + " (in " + catalog.parentOf(location.getKey()).orElse("requested area") + ")");
            }
            if (!anyHit) return null;
            if (!anyVerified) doubts.add("location is not verified for this account");
            if (catalog.expand(query.inferred()).containsAll(intersection(record.locations().keySet(), query.expandedLocations())))
                doubts.add("location was read from a misheard or ambiguous word");
        }

        for (RecipientQuery.Keyword keyword : query.keywords()) {
            String term = keyword.term();
            if (record.keywords().contains(term)) {
                terms.add(term);
            } else if (term.length() >= 4 && record.keywords().stream().anyMatch(token -> token.startsWith(term))) {
                terms.add(term);
                doubts.add("'" + term + "' matched only part of a word");
            } else if (record.keywords().stream().anyMatch(keyword.fuzzyForms()::contains)) {
                terms.add(term);
                doubts.add("'" + term + "' matched a similar spelling");
            } else {
                return null;
            }
        }

        if (doubts.isEmpty()) return new Ranked(CONFIRMED, "TOKEN_MATCH", List.copyOf(terms),
                "Matched " + String.join(", ", terms), 0.95);
        double score = Math.max(0.5, 0.75 - 0.05 * (doubts.size() - 1));
        return new Ranked(SUGGESTED, "POSSIBLE_MATCH", List.copyOf(terms),
                "Verify before sending: " + String.join("; ", doubts), score);
    }

    private static boolean excluded(ParsedRecipient record, RecipientQuery query) {
        if (!intersection(record.designations().keySet(), query.excludedDesignations()).isEmpty()) return true;
        if (!intersection(record.locations().keySet(), query.excludedLocations()).isEmpty()) return true;
        // Exclusions err on the side of leaving someone out: a partial-word hit also excludes.
        return query.excludedKeywords().stream().anyMatch(term -> record.keywords().contains(term)
                || term.length() >= 4 && record.keywords().stream().anyMatch(token -> token.startsWith(term)));
    }

    private String labels(Set<String> codes) {
        return String.join(", ", codes.stream().map(catalog::label).toList());
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return result;
    }
}
