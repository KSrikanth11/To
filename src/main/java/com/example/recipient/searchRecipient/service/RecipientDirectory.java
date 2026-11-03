package com.example.recipient.searchRecipient.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;

/** Source of one list's catalog and its parsed members and subgroups. */
public interface RecipientDirectory {
    /** The list to answer one request from. */
    View snapshot(Long listId);

    /** Drops any cached lists and reports the counts now loaded for the default list. */
    Status reload();

    /** One list as a request sees it: the catalog that reads the query, and the records that may match it. */
    interface View {
        long listId();

        CatalogEngine engine();

        RecipientMatcher.Vocabulary words();

        /** Every member RecipientMatcher.match could accept for this query; usually far fewer than the list. */
        List<ParsedRecipient> memberCandidates(RecipientQuery query);

        List<ParsedRecipient> subgroupCandidates(RecipientQuery query);
    }

    /**
     * A list held in memory (the JSON index source): its rows parsed with that list's own catalog; the engine reads queries for the same list. The
     * vocabulary is sorted so prefix lookups stay fast, and the indexes narrow a query to its candidates.
     */
    record Snapshot(long listId, CatalogEngine engine, List<ParsedRecipient> members, List<ParsedRecipient> subgroups,
                    NavigableSet<String> vocabulary, CandidateIndex memberIndex, CandidateIndex subgroupIndex,
                    Instant loadedAt) implements View {
        public static Snapshot of(long listId, CatalogEngine engine, List<ParsedRecipient> members,
                                  List<ParsedRecipient> subgroups) {
            NavigableSet<String> vocabulary = new TreeSet<>();
            members.forEach(record -> vocabulary.addAll(record.keywords()));
            subgroups.forEach(record -> vocabulary.addAll(record.keywords()));
            List<ParsedRecipient> memberRows = List.copyOf(members), subgroupRows = List.copyOf(subgroups);
            return new Snapshot(listId, engine, memberRows, subgroupRows, Collections.unmodifiableNavigableSet(vocabulary),
                    new CandidateIndex(memberRows), new CandidateIndex(subgroupRows), Instant.now());
        }

        public RecipientMatcher.Vocabulary words() { return RecipientMatcher.Vocabulary.of(vocabulary); }

        public List<ParsedRecipient> memberCandidates(RecipientQuery query) { return memberIndex.candidates(query, vocabulary); }

        public List<ParsedRecipient> subgroupCandidates(RecipientQuery query) { return subgroupIndex.candidates(query, vocabulary); }

        public Status status() { return new Status(listId, members.size(), subgroups.size(), loadedAt); }
    }

    record Status(long list_id, int member_count, int subgroup_count, Instant loaded_at) { }
}
