package com.example.recipient.service;

import com.example.recipient.model.Intent;
import com.example.recipient.TestIndexFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchingServiceTest {
    private final AliasService aliases = new AliasService("", TestIndexFactory.load());
    private final MatchingService matching = new MatchingService(0.58);

    @Test void usernameMatchesLocationAndDesignationAliases() {
        Intent intent = new Intent("Send to Nalgonda all SIs", aliases.concepts("Send to Nalgonda all SIs"), true);
        var result = matching.rank("example-si-nlg", intent, false, null);
        assertNotNull(result);
        assertEquals(0.98, result.score());
        assertEquals("TOKEN_MATCH", result.type());
        assertEquals(List.of("Nalgonda", "Sub-Inspector"), result.terms());
    }

    @Test void subgroupDescriptionCanSupplyARequestedConcept() {
        Intent intent = new Intent("Nalgonda SI", aliases.concepts("Nalgonda SI"), false);
        var result = matching.rank("Sub Inspector group", intent, true, "Nalgonda units");
        assertNotNull(result);
        assertEquals(0.99, result.score());
    }

    @Test void missingEssentialConceptIsMarkedPossibleAndScoresLower() {
        Intent intent = new Intent("Nalgonda SI", aliases.concepts("Nalgonda SI"), false);
        var result = matching.rank("NLG patrol", intent, false, null);
        assertNotNull(result);
        assertEquals("POSSIBLE_MATCH", result.type());
        assertTrue(result.score() < 0.9);
    }

    @Test void configuredAliasesNormalizePunctuationAndWordOrder() {
        AliasService configured = new AliasService("Rural=Rural Division|RUR", TestIndexFactory.load());
        assertTrue(configured.concepts("rural_division").stream().anyMatch(c -> c.label().equals("Rural")));
    }

    @Test void misspellingsArePossibleMatchesAndNeedVerification() {
        Intent intent = new Intent("Nalgoda", aliases.concepts("Nalgoda"), false);
        var result = matching.rank("Nalgonda", intent, false, null);
        assertNotNull(result);
        assertEquals("POSSIBLE_MATCH", result.type());
        assertTrue(result.score() <= 0.84);
    }

    @Test void designationAliasesIncludeCiDspAndSdpo() {
        assertTrue(aliases.concepts("Circle Inspectors").stream().anyMatch(c -> c.label().equals("Circle Inspector")));
        assertTrue(aliases.concepts("DSP").stream().anyMatch(c -> c.label().equals("Deputy Superintendent of Police")));
        assertTrue(aliases.concepts("SDPO").stream().anyMatch(c -> c.label().equals("Sub-Divisional Police Officer")));
    }

    @Test void contradictoryLocationAndDesignationDoNotMatch() {
        Intent intent = new Intent("Nalgonda SI", aliases.concepts("Nalgonda SI"), false);
        assertNull(matching.rank("Warangal-CI", intent, false, null));
    }
}
