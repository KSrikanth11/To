package com.example.recipient.searchRecipient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.MemberProfile;
import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;

/** Matching through decoded member profiles and a catalog shaped like recipient_decoded_import.sql. */
class DecodedProfileMatchingTest {
    private static final String NALGONDA = "Nalgonda District", MULUGU = "Mulugu District",
            MIRYALAGUDA = "Miryalaguda, Nalgonda District";

    private final RecipientCatalog catalog = catalog();
    private final CatalogEngine engine = CatalogEngine.of(catalog);

    private static RecipientCatalog catalog() {
        List<RecipientCatalog.Phrase> rows = new ArrayList<>();
        designation(rows, "SI", "Sub-Inspector", null, "si", "sis", "sub inspector");
        designation(rows, "WSI", "Woman Sub-Inspector", "SI", "wsi", "wsis");
        designation(rows, "HC", "Head Constable", null, "hc", "hcs", "head constables");
        location(rows, NALGONDA, true, "nalgonda", "nalgonda district", "nlg");
        location(rows, MULUGU, true, "mulugu", "mulugu district", "mlg");
        location(rows, MIRYALAGUDA, true, "miryalaguda", "mlg");      // mlg names two places
        return RecipientCatalog.fromRows(rows, RecipientCatalog.DEFAULT_QUERY_WORDS, "");
    }

    private static void designation(List<RecipientCatalog.Phrase> rows, String code, String label, String parent, String... aliases) {
        for (String alias : aliases) rows.add(new RecipientCatalog.Phrase(RecipientCatalog.Kind.DESIGNATION, code, label, alias, true, parent));
    }

    private static void location(List<RecipientCatalog.Phrase> rows, String name, boolean verified, String... aliases) {
        for (String alias : aliases) rows.add(new RecipientCatalog.Phrase(RecipientCatalog.Kind.LOCATION, name, name, alias, verified));
    }

    private static MemberProfile profile(long id, String username, String confidence, Map<String, Boolean> designations,
                                         Map<String, Boolean> locations, String... texts) {
        return new MemberProfile(id, username, confidence, List.of(texts), designations, locations);
    }

    private RecipientQuery query(String text) {
        RecipientQuery interpreted = engine.interpreter().interpret(
                new Intent(text, List.of(text), false, Intent.RecipientType.AUTO));
        return engine.matcher().prepare(interpreted, java.util.Set.of("si", "mlg", "nlg", "ahtu"));
    }

    @Test void decodedProfileDecidesWhereTheAccountBelongs() {
        // si-ahtu-mlg is a Mulugu District account: the code alone could also mean Miryalaguda.
        ParsedRecipient mulugu = engine.parser().member(1, "si-ahtu-mlg@suraksha", profile(1, "si-ahtu-mlg@suraksha",
                "CONFIDENT", Map.of("SI", true), Map.of(MULUGU, true), "Sub-Inspector (SI)", "Mulugu District"));
        assertEquals(RecipientMatcher.CONFIRMED, engine.matcher().match(mulugu, query("SIs in Mulugu")).status());
        assertNull(engine.matcher().match(mulugu, query("SIs in Nalgonda")));
        assertNull(engine.matcher().match(mulugu, query("SIs in Miryalaguda")));
    }

    @Test void uncertainDecodedValuesOnlySuggest() {
        ParsedRecipient unsure = engine.parser().member(2, "si-x-nlg@suraksha", profile(2, "si-x-nlg@suraksha",
                "LIKELY", Map.of("SI", true), Map.of(NALGONDA, false), "Sub-Inspector (SI)", "Nalgonda District?"));
        var match = engine.matcher().match(unsure, query("SIs in Nalgonda"));
        assertEquals(RecipientMatcher.SUGGESTED, match.status());
        assertTrue(match.reason().contains("not verified"), match.reason());

        ParsedRecipient unsureRank = engine.parser().member(3, "si-y-nlg@suraksha", profile(3, "si-y-nlg@suraksha",
                "NEEDS_REVIEW", Map.of("SI", false), Map.of(NALGONDA, false)));
        assertEquals(RecipientMatcher.SUGGESTED, engine.matcher().match(unsureRank, query("SIs in Nalgonda")).status());
    }

    @Test void systemAccountsNeverMatchARankOrPlace() {
        ParsedRecipient system = engine.parser().member(4, "si-test-nlg@suraksha", profile(4, "si-test-nlg@suraksha",
                "SYSTEM", Map.of("SI", true), Map.of(NALGONDA, true)));
        assertNull(engine.matcher().match(system, query("SIs in Nalgonda")));
        assertEquals("EXACT_MATCH", engine.matcher().match(system, query("si-test-nlg@suraksha")).type());
    }

    @Test void staleProfileIsIgnoredAfterARename() {
        MemberProfile old = profile(5, "si-old-mlg@suraksha", "CONFIDENT", Map.of("SI", true), Map.of(MULUGU, true));
        ParsedRecipient renamed = engine.parser().member(5, "si-new-nlg@suraksha", old);
        assertEquals(Map.of(NALGONDA, true), renamed.locations(), "read from the current username, not the old profile");
    }

    @Test void widerRankIncludesNarrowerOnesButNotTheReverse() {
        ParsedRecipient wsi = engine.parser().member(6, "wsi-x-nlg@suraksha", profile(6, "wsi-x-nlg@suraksha",
                "CONFIDENT", Map.of("WSI", true), Map.of(NALGONDA, true)));
        ParsedRecipient si = engine.parser().member(7, "si-x-nlg@suraksha", profile(7, "si-x-nlg@suraksha",
                "CONFIDENT", Map.of("SI", true), Map.of(NALGONDA, true)));
        assertNotNull(engine.matcher().match(wsi, query("SIs in Nalgonda")), "a WSI is an SI");
        assertNotNull(engine.matcher().match(wsi, query("WSIs in Nalgonda")));
        assertNull(engine.matcher().match(si, query("WSIs in Nalgonda")), "an SI is not a WSI");
    }

    @Test void codeWithSeveralMeaningsIsAskedAbout() {
        RecipientQuery query = query("SIs in MLG");
        assertEquals(java.util.Set.of(MULUGU, MIRYALAGUDA), query.locations());
        assertTrue(query.unresolved().get(0).ambiguous());

        ParsedRecipient mulugu = engine.parser().member(8, "si-ahtu-mlg@suraksha", profile(8, "si-ahtu-mlg@suraksha",
                "CONFIDENT", Map.of("SI", true), Map.of(MULUGU, true)));
        assertEquals(RecipientMatcher.SUGGESTED, engine.matcher().match(mulugu, query).status());

        var directory = new RecipientDirectory() {
            public Snapshot snapshot(Long listId) { return Snapshot.of(173, engine, List.of(mulugu), List.of()); }
            public Status reload() { return snapshot(null).status(); }
        };
        var response = new RecipientResolverService(new DeterministicIntentParser(), directory, 200)
                .resolve(new ResolveRecipientRequest("SIs in MLG", 50, 0));
        assertTrue(response.needs_clarification());
        assertTrue(response.clarification().contains("Mulugu District") && response.clarification().contains("Miryalaguda"),
                response.clarification());
    }

    @Test void usernameWithoutProfileStillReadsAmbiguousCodesAsSuggestions() {
        ParsedRecipient noProfile = engine.parser().member(9, "si-ahtu-mlg@suraksha");
        assertEquals(Map.of(MULUGU, false, MIRYALAGUDA, false), noProfile.locations());
        assertEquals(RecipientMatcher.SUGGESTED, engine.matcher().match(noProfile, query("SIs in Mulugu")).status());
    }
}
