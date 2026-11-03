package com.example.recipient.searchRecipient.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.RecipientQuery;

/**
 * Turns extracted terms into typed filters using only the list's catalog, including its query words.
 * Words that carry no search meaning are dropped; anything left over becomes a required keyword
 * instead of being guessed at.
 */
public class QueryInterpreter {
    private final RecipientCatalog catalog;

    public QueryInterpreter(RecipientCatalog catalog) { this.catalog = catalog; }

    public RecipientQuery interpret(Intent intent) {
        Filters include = new Filters();
        Filters exclude = new Filters();
        for (String term : intent.searchTerms()) {
            List<String> segment = new ArrayList<>();
            Filters target = include;
            for (String token : TextNormalizer.tokens(TextNormalizer.stripDomain(term))) {
                if (catalog.isQueryWord(RecipientCatalog.EXCLUSION, token)) {
                    read(segment, target);
                    segment.clear();
                    target = exclude;
                } else {
                    segment.add(token);
                }
            }
            read(segment, target);
        }
        for (String term : intent.excludeTerms()) read(TextNormalizer.tokens(TextNormalizer.stripDomain(term)), exclude);

        Intent.RecipientType type = intent.recipientType();
        if (include.groupHint && !include.memberHint && type != Intent.RecipientType.MEMBERS) type = Intent.RecipientType.GROUPS;
        else if (include.memberHint && !include.groupHint && type != Intent.RecipientType.GROUPS) type = Intent.RecipientType.MEMBERS;

        String exactKey = TextNormalizer.normalize(intent.searchTerms().stream()
                .map(TextNormalizer::stripDomain).collect(Collectors.joining(" ")));
        // A word naming several places ("mlg") keeps every meaning but only as a suggestion, and is asked about.
        Set<String> uncertain = new LinkedHashSet<>();
        List<RecipientQuery.UnresolvedTerm> unresolved = new ArrayList<>();
        include.ambiguous.forEach((phrase, keys) -> {
            uncertain.addAll(keys);
            unresolved.add(new RecipientQuery.UnresolvedTerm(phrase, keys.stream().map(catalog::label).toList(), true));
        });
        return new RecipientQuery(Set.copyOf(include.designations), Set.copyOf(include.locations),
                catalog.expand(include.locations),
                include.keywords.stream().map(RecipientQuery.Keyword::new).toList(),
                Set.copyOf(exclude.designations), catalog.expand(exclude.locations), List.copyOf(exclude.keywords),
                Set.copyOf(uncertain), List.copyOf(unresolved), intent.allScope() || include.all, type, exactKey);
    }

    private void read(List<String> tokens, Filters filters) {
        if (tokens.isEmpty()) return;
        RecipientCatalog.Annotation annotation = catalog.annotate(tokens);
        filters.designations.addAll(annotation.designations());
        filters.locations.addAll(annotation.locations().keySet());
        filters.ambiguous.putAll(annotation.ambiguous());
        filters.all |= annotation.allPrefix();
        for (String token : annotation.rest()) {
            if (catalog.isQueryWord(RecipientCatalog.GROUP_HINT, token)) filters.groupHint = true;
            if (catalog.isQueryWord(RecipientCatalog.MEMBER_HINT, token)) filters.memberHint = true;
            if (catalog.isQueryWord(RecipientCatalog.ALL_SCOPE, token)) filters.all = true;
            if (!catalog.isIgnoredWord(token) && token.length() > 1) filters.keywords.add(catalog.unitKeyword(token));
        }
    }

    private static final class Filters {
        final Set<String> designations = new LinkedHashSet<>();
        final Set<String> locations = new LinkedHashSet<>();
        final Set<String> keywords = new LinkedHashSet<>();
        final Map<String, List<String>> ambiguous = new LinkedHashMap<>();
        boolean all, groupHint, memberHint;
    }
}
