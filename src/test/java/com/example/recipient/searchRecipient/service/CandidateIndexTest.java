package com.example.recipient.searchRecipient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;
import com.example.recipient.searchRecipient.service.RecipientDirectory.Snapshot;

/** The index must hand the matcher every record a full scan would accept, so results do not change. */
class CandidateIndexTest {
    private static final List<String> QUERIES = List.of("Send to all SIs in Nalgonda", "SHOs in Cyberabad except traffic",
            "SIs in MLG", "Inspectors in Khammam", "WSIs in Hyderabad", "SHOs in Nalgoda", "CIs in Medak",
            "traffic SIs", "ahtu", "send to cyber", "groups of SIs in Karimnagar", "DSPs except Nalgonda",
            "Ramesh Kumar", "Rames", "kumar", "si-ahtu-mlg@suraksha", "zzzz", "all sis");

    @Test void candidatesThenMatchEqualsAFullScan() {
        Snapshot snapshot = new IndexRecipientDirectory(TestIndexFactory.load(), CatalogEngine.of(TestIndexFactory.catalog()))
                .snapshot(null);
        CatalogEngine engine = snapshot.engine();
        int matched = 0;
        for (String text : QUERIES) {
            RecipientQuery query = engine.matcher().prepare(engine.interpreter().interpret(
                    new DeterministicIntentParser().parse(text)), snapshot.vocabulary());
            assertEquals(scan(engine, snapshot.members(), query), scan(engine, snapshot.memberCandidates(query), query), text);
            assertEquals(scan(engine, snapshot.subgroups(), query), scan(engine, snapshot.subgroupCandidates(query), query), text);
            matched += scan(engine, snapshot.members(), query).size();
        }
        assertTrue(matched > 300, "the queries exercise real matches: " + matched);
    }

    @Test void plainNamesAreFoundByWordPrefixAndExactName() {
        CatalogEngine engine = CatalogEngine.of(TestIndexFactory.catalog());
        List<ParsedRecipient> members = List.of(engine.parser().member(1, "ramesh.kumar@abc"),
                engine.parser().member(2, "rameshwar.rao@abc"), engine.parser().member(3, "priya.sharma@abc"));
        Snapshot snapshot = Snapshot.of(9, engine, members, List.of());
        for (String text : List.of("Ramesh", "rame", "ramesh kumar", "priya.sharma@abc", "kumar")) {
            RecipientQuery query = engine.matcher().prepare(engine.interpreter().interpret(
                    new Intent(text, List.of(text), false, Intent.RecipientType.AUTO)), snapshot.vocabulary());
            assertEquals(scan(engine, members, query), scan(engine, snapshot.memberCandidates(query), query), text);
        }
    }

    private static List<String> scan(CatalogEngine engine, List<ParsedRecipient> records, RecipientQuery query) {
        List<String> out = new ArrayList<>();
        for (ParsedRecipient record : records) {
            RecipientMatcher.Ranked rank = engine.matcher().match(record, query);
            if (rank != null) out.add(record.id() + ":" + rank.status() + ":" + Objects.hash(rank.terms(), rank.reason()));
        }
        return out;
    }
}
