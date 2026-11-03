package com.example.recipient.searchRecipient.service;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.function.Function;

import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;

/**
 * Lookup tables from designation, location, keyword and exact name to the records holding them. A query
 * reads only the records that can pass every filter, so RecipientMatcher.match sees the same records it
 * would accept from a full scan, without visiting the rest of a large list.
 */
public final class CandidateIndex {
    private final List<ParsedRecipient> records;
    private final Map<String, int[]> byDesignation;
    private final Map<String, int[]> byLocation;
    private final Map<String, int[]> byKeyword;
    private final Map<String, int[]> byExactKey;

    public CandidateIndex(List<ParsedRecipient> records) {
        this.records = records;
        this.byDesignation = build(records, record -> record.designations().keySet());
        this.byLocation = build(records, record -> record.locations().keySet());
        this.byKeyword = build(records, ParsedRecipient::keywords);
        this.byExactKey = build(records, record -> record.exactKey() == null || record.exactKey().isEmpty()
                ? List.of() : List.of(record.exactKey()));
    }

    /** Records that may match, in list order: every filter narrows the set, an exact name adds to it. */
    public List<ParsedRecipient> candidates(RecipientQuery query, NavigableSet<String> vocabulary) {
        BitSet hits = null;
        if (!query.designations().isEmpty()) hits = narrow(hits, union(byDesignation, query.designations()));
        if (!query.locations().isEmpty()) hits = narrow(hits, union(byLocation, query.expandedLocations()));
        for (RecipientQuery.Keyword keyword : query.keywords()) {
            String term = keyword.term();
            BitSet forms = union(byKeyword, keyword.fuzzyForms());
            or(forms, byKeyword.get(term));
            // RecipientMatcher also accepts a word that only starts with a term of four or more letters.
            if (term.length() >= 4)
                for (String token : vocabulary.subSet(term, false, term + Character.MAX_VALUE, false)) or(forms, byKeyword.get(token));
            hits = narrow(hits, forms);
        }
        if (hits == null) hits = new BitSet();
        if (!query.exactKey().isEmpty()) or(hits, byExactKey.get(query.exactKey()));

        List<ParsedRecipient> out = new ArrayList<>(hits.cardinality());
        for (int i = hits.nextSetBit(0); i >= 0; i = hits.nextSetBit(i + 1)) out.add(records.get(i));
        return out;
    }

    private static Map<String, int[]> build(List<ParsedRecipient> records, Function<ParsedRecipient, Collection<String>> keys) {
        // Two passes so each key gets an exactly sized array: count, then fill.
        Map<String, int[]> index = new HashMap<>();
        for (ParsedRecipient record : records) for (String key : keys.apply(record)) index.merge(key, new int[]{1}, (a, b) -> { a[0]++; return a; });
        index.replaceAll((key, count) -> new int[count[0]]);
        Map<String, Integer> filled = new HashMap<>();
        for (int i = 0; i < records.size(); i++)
            for (String key : keys.apply(records.get(i))) index.get(key)[filled.merge(key, 1, Integer::sum) - 1] = i;
        return Map.copyOf(index);
    }

    private static BitSet union(Map<String, int[]> index, Collection<String> keys) {
        BitSet out = new BitSet();
        keys.forEach(key -> or(out, index.get(key)));
        return out;
    }

    private static void or(BitSet target, int[] ids) {
        if (ids != null) for (int id : ids) target.set(id);
    }

    private static BitSet narrow(BitSet current, BitSet next) {
        if (current == null) return next;
        current.and(next);
        return current;
    }
}
