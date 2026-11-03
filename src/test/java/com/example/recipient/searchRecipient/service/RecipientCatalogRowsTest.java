package com.example.recipient.searchRecipient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;

/** A catalog built from table rows must behave exactly like the built-in defaults the tables are seeded from. */
class RecipientCatalogRowsTest {
    private static final List<String> QUERIES = List.of(
            "Send to all SIs in Nalgonda", "Nalgonda Circle Inspectors", "DSPs in Nalgonda", "SDPOs in Nalgonda district",
            "all SHOs in Cyberabad except traffic", "CIs in Medak", "SHOs in Karimnagar", "traffic ACPs in Malkajgiri",
            "Find all officers in MLG", "SIs in Nalgoda", "inspectors in khammam", "select all groups in Nalgonda",
            "ACPs in Kukatpally zone", "send to itc staff in sangareddy", "L&O SHOs Kukatpally", "zzxxyyqwert",
            "send it to everyone please", "12bn-anneparthy-nlg@suraksha", "ALLSDPOs-NLG", "members in Suryapet");

    @Test void catalogFromRowsResolvesExactlyLikeTheDefaults() {
        RecipientCatalog defaults = TestIndexFactory.catalog();
        RecipientCatalog fromRows = RecipientCatalog.fromRows(defaults.rows(), defaults.queryWords(), "");
        RecipientResolverService expected = TestIndexFactory.resolver(TestIndexFactory.directory(defaults));
        RecipientResolverService actual = TestIndexFactory.resolver(TestIndexFactory.directory(fromRows));
        for (String query : QUERIES) {
            ResolveRecipientRequest request = new ResolveRecipientRequest(query, 200, 0);
            assertEquals(expected.resolve(request), actual.resolve(request), query);
        }
    }

    @Test void queryWordsComeFromTheCatalog() {
        RecipientCatalog defaults = TestIndexFactory.catalog();
        Map<String, List<String>> words = Map.of(
                RecipientCatalog.COMMAND, List.of("pampu"),          // a tenant-specific command word
                RecipientCatalog.EXCLUSION, List.of("except"));
        RecipientCatalog custom = RecipientCatalog.fromRows(defaults.rows(), words, "");
        assertTrue(custom.isIgnoredWord("pampu"));
        assertFalse(custom.isIgnoredWord("send"), "words not in this list's table are not ignored");
        assertFalse(defaults.isIgnoredWord("pampu"));
    }

    @Test void catalogWithoutRowsIsEmpty() {
        assertTrue(RecipientCatalog.fromRows(List.of(), Map.of(), "").isEmpty());
        RecipientCatalog defaults = TestIndexFactory.catalog();
        assertTrue(RecipientCatalog.fromRows(defaults.rows(), Map.of(), "").isEmpty(), "query words are required too");
        assertFalse(defaults.isEmpty());
    }
}
