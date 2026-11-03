package com.example.recipient.searchRecipient.model;

import java.util.List;
import java.util.Set;

/**
 * Typed filters interpreted from an intent. Designations are alternatives (SI or CI), locations are
 * alternatives after hierarchy expansion, and every keyword is required.
 */
public record RecipientQuery(Set<String> designations, Set<String> locations, Set<String> expandedLocations,
                             List<Keyword> keywords, Set<String> excludedDesignations,
                             Set<String> excludedLocations, List<String> excludedKeywords,
                             Set<String> inferred, List<UnresolvedTerm> unresolved,
                             boolean allScope, Intent.RecipientType recipientType, String exactKey) {

    /** A required word; fuzzyForms holds near spellings when the word itself is not in the directory. */
    public record Keyword(String term, Set<String> fuzzyForms) {
        public Keyword(String term) { this(term, Set.of()); }
    }

    /** A word that matched nothing (suggestions = near spellings) or that names several places (ambiguous). */
    public record UnresolvedTerm(String term, List<String> suggestions, boolean ambiguous) {
        public UnresolvedTerm(String term, List<String> suggestions) { this(term, suggestions, false); }
    }

    public boolean hasFilters() {
        return !designations.isEmpty() || !locations.isEmpty() || !keywords.isEmpty();
    }
}
