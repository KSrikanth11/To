package com.example.recipient.searchRecipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.RecipientQuery;
import com.example.recipient.searchRecipient.service.CatalogEngine;

class CandidateSqlTest {
    private final CatalogEngine engine = CatalogEngine.of(TestIndexFactory.catalog());

    private RecipientQuery query(String text) {
        return engine.matcher().prepare(engine.interpreter().interpret(
                new Intent(text, List.of(text), false, Intent.RecipientType.AUTO)), Set.of("ramesh", "kumar"));
    }

    @Test void patternsKeepWordOrderAndTheSpellingsTheNormalizerJoins() {
        assertEquals(Set.of("%sub%inspector%"), CandidateSql.likes("sub inspector"));
        assertEquals(Set.of("%lo%", "%l%o%", "%law%order%"), CandidateSql.likes("lo"));
        assertTrue(CandidateSql.likes("officers").contains("%officer%s%"), "officer's reads as officers");
        assertEquals(Set.of("%colony%"), CandidateSql.likes("colony"), "only the whole word lo is L&O");
    }

    @Test void coveredPatternsAreDropped() {
        assertTrue(CandidateSql.covers("%si%", "%wsis%"));
        assertTrue(CandidateSql.covers("%sub%inspector%", "%subinspectors%"));
        assertFalse(CandidateSql.covers("%sub%inspector%", "%si%"));
        assertFalse(CandidateSql.covers("%ab%", "%a%b%"));
        assertEquals(List.of("%si%", "%sub%inspector%"),
                CandidateSql.withoutCovered(List.of("%si%", "%sis%", "%wsi%", "%sub%inspector%", "%subinspector%")));
    }

    @Test void everyFilterIsRequiredAndProfilesCanSupplyIt() {
        CandidateSql usernames = CandidateSql.usernames(173, query("SIs in Nalgonda"), engine.catalog());
        assertTrue(usernames.where.contains(") AND ("), usernames.where);
        assertFalse(usernames.where.contains("recipient_member_attribute"), "the list_members scan reads no other table");
        assertTrue(usernames.args.contains("%si%") && usernames.args.contains("%nalgonda%"), usernames.args.toString());

        CandidateSql profiled = CandidateSql.profiled(173, query("SIs in Nalgonda"), engine.catalog());
        assertTrue(profiled.where.contains("a.designation_code IN") && profiled.where.contains("l.name IN"), profiled.where);
    }

    @Test void plainNamesSearchByWordsOrTheExactName() {
        CandidateSql sql = CandidateSql.usernames(9, query("Ramesh Kumar"), engine.catalog());
        assertTrue(sql.args.containsAll(List.of("%ramesh%", "%kumar%", "%ramesh%kumar%")), sql.args.toString());
        assertTrue(sql.where.contains(" OR "), "the exact name is an alternative to the word filters");
    }

    @Test void noFiltersSelectsNothingButAnExactName() {
        CandidateSql sql = CandidateSql.usernames(9, query("please send"), engine.catalog());
        assertTrue(sql.where.startsWith("(FALSE)") || sql.where.equals("FALSE"), sql.where);
    }
}
