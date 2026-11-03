package com.example.recipient.searchRecipient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;

class RecipientMatcherTest {
    private final RecipientCatalog catalog = TestIndexFactory.catalog();
    private final RecipientParser parser = new RecipientParser(catalog);
    private final QueryInterpreter interpreter = new QueryInterpreter(catalog);
    private final RecipientMatcher matcher = new RecipientMatcher(catalog);

    private RecipientQuery query(String... terms) {
        return query(List.of(), terms);
    }

    private RecipientQuery query(List<String> excluded, String... terms) {
        RecipientQuery interpreted = interpreter.interpret(new Intent(String.join(" ", terms), List.of(terms), excluded,
                false, Intent.RecipientType.AUTO));
        return matcher.prepare(interpreted, Set.of("si", "ahtu", "nlg", "traffic", "itc", "ccs", "nalgonda", "inspr"));
    }

    private ParsedRecipient member(String username) { return parser.member(1, username); }

    @Test void designationAndLocationMustBothMatchOnTheirOwnFields() {
        RecipientQuery query = query("SIs", "Nalgonda");
        var match = matcher.match(member("si-ahtu-nlg@suraksha"), query);
        assertNotNull(match);
        assertEquals(RecipientMatcher.CONFIRMED, match.status());
        assertEquals(List.of("Sub-Inspector", "Nalgonda"), match.terms());

        assertNull(matcher.match(member("ci-ahtu-nlg@suraksha"), query), "wrong designation");
        assertNull(matcher.match(member("si-ahtu-wgl@suraksha"), query), "wrong district");
        assertNull(matcher.match(member("itc-ahtu-nlg@suraksha"), query), "no designation at all");
    }

    @Test void numberedDesignationCodesAreRecognized() {
        RecipientQuery query = query("SI", "Nalgonda");
        assertEquals(RecipientMatcher.CONFIRMED, matcher.match(member("si1046-ps-nlg@suraksha"), query).status());
        assertEquals(RecipientMatcher.CONFIRMED, matcher.match(member("itc-si2000-nlg@suraksha"), query).status());
        assertEquals(Set.of("CI"), member("ci1-ccs-nlg@suraksha").designations().keySet());
        assertEquals(Set.of("INSPR"), member("inspr3-csb-kmm@suraksha").designations().keySet());
    }

    @Test void eachLocationMatchesOnlyItselfWhileTheHierarchyIsDisabled() {
        assertNull(matcher.match(member("si-ahtu-mlg@suraksha"), query("SIs in Nalgonda")));
        assertEquals(RecipientMatcher.CONFIRMED, matcher.match(member("si-ahtu-mlg@suraksha"), query("SIs in Miryalaguda")).status());
    }

    @Test void configuredHierarchyIncludesSubDivisions() {
        RecipientCatalog configured = TestIndexFactory.catalog("", "Miryalaguda=Nalgonda");
        RecipientQuery query = new QueryInterpreter(configured).interpret(
                new Intent("SIs in Nalgonda", List.of("SIs in Nalgonda"), false, Intent.RecipientType.AUTO));
        var match = new RecipientMatcher(configured).match(new RecipientParser(configured).member(1, "si-ahtu-mlg"), query);
        assertEquals(RecipientMatcher.CONFIRMED, match.status());
        assertTrue(match.terms().contains("Miryalaguda (in Nalgonda)"));
    }

    @Test void fillerWordsDoNotBecomeRequiredFilters() {
        RecipientQuery query = query("Send to all SI officers in Nalgonda district police station");
        assertTrue(query.keywords().isEmpty(), () -> "unexpected keywords " + query.keywords());
        assertTrue(query.allScope());
        assertNotNull(matcher.match(member("si-ahtu-nlg@suraksha"), query));
    }

    @Test void unknownWordsAreReportedInsteadOfDropped() {
        RecipientQuery query = query("SIs", "Nalgonda", "zzqx");
        assertEquals("zzqx", query.unresolved().get(0).term());
        assertNull(matcher.match(member("si-ahtu-nlg@suraksha"), query));
    }

    @Test void misheardLocationOnlyProducesSuggestions() {
        RecipientQuery query = query("SIs in Nalgoda");
        assertTrue(query.locations().contains("Nalgonda"));
        assertEquals(List.of("Nalgonda"), query.unresolved().get(0).suggestions());
        assertEquals(RecipientMatcher.SUGGESTED, matcher.match(member("si-ahtu-nlg@suraksha"), query).status());
    }

    @Test void unverifiedLocationCodesOnlyProduceSuggestions() {
        var medak = matcher.match(member("ci-ccs-mdk@suraksha"), query("CIs in Medak"));
        assertEquals(RecipientMatcher.SUGGESTED, medak.status());
        assertTrue(medak.reason().contains("not verified"));

        // KNR is listed as unverified in the dictionary even though it also appears as a Karimnagar alias.
        assertEquals(RecipientMatcher.SUGGESTED, matcher.match(member("sho-x-knr@suraksha"), query("SHOs in Karimnagar")).status());
        assertEquals(RecipientMatcher.CONFIRMED, matcher.match(member("sho-x-kmr@suraksha"), query("SHOs in Karimnagar")).status());
    }

    @Test void configuredAliasConfirmsAnUnverifiedCode() {
        RecipientCatalog configured = TestIndexFactory.catalog("Medak=MDK");
        RecipientQuery query = new QueryInterpreter(configured).interpret(
                new Intent("CIs in Medak", List.of("CIs in Medak"), false, Intent.RecipientType.AUTO));
        var match = new RecipientMatcher(configured).match(new RecipientParser(configured).member(1, "ci-ccs-mdk"), query);
        assertEquals(RecipientMatcher.CONFIRMED, match.status());
    }

    @Test void groupsWithExtraDesignationsAreOnlySuggested() {
        ParsedRecipient allCis = parser.subgroup(2, "ALLCIs-NLG", "All 10 Circle Inspectors and 06 Inspectors in Nalgonda");
        ParsedRecipient allSdpos = parser.subgroup(3, "ALLSDPOs-NLG", "SDPO Officers in Nalgonda District");
        assertEquals(RecipientMatcher.SUGGESTED, matcher.match(allCis, query("CIs in Nalgonda")).status());
        assertNull(matcher.match(allCis, query("SIs in Nalgonda")));
        assertEquals(RecipientMatcher.CONFIRMED, matcher.match(allSdpos, query("SDPOs in Nalgonda")).status());
    }

    @Test void groupsWithoutTheRequestedLocationDoNotMatch() {
        ParsedRecipient noLocation = parser.subgroup(4, "asifabadshoandcianddsp", "ALL L&O SHO'S,CI'S AND DSP'S");
        assertNull(matcher.match(noLocation, query("SHOs in Nalgonda")));
    }

    @Test void exclusionsUseUnitSpellingsFromTheDirectory() {
        RecipientQuery query = query(List.of("traffic"), "SHOs", "Cyberabad");
        assertNull(matcher.match(member("sho-blnr-tfc-kz-cyb@suraksha"), query));
        assertNull(matcher.match(member("sho-trafficps-cyb@suraksha"), query));
        assertNotNull(matcher.match(member("sho-blnr-lo-kz-cyb@suraksha"), query));
        RecipientQuery inline = query("SHOs in Cyberabad except traffic");
        assertEquals(List.of("traffic"), inline.excludedKeywords());
    }

    @Test void exactUsernameIsAnExactMatch() {
        var match = matcher.match(member("12bn-anneparthy-nlg@suraksha"), query("12bn-anneparthy-nlg@suraksha"));
        assertEquals("EXACT_MATCH", match.type());
        assertEquals(1.0, match.score());
    }

    @Test void groupWordsSelectGroups() {
        assertEquals(Intent.RecipientType.GROUPS, query("all groups in Nalgonda").recipientType());
        assertEquals(Intent.RecipientType.MEMBERS, query("members in Nalgonda").recipientType());
    }
}
