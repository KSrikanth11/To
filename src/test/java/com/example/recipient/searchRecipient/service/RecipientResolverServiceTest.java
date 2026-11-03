package com.example.recipient.searchRecipient.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.mock.web.MockMultipartFile;

import com.example.recipient.AudioService.SonioxTranscriptionService;
import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.RecipientController;
import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.MemberMatch;
import com.example.recipient.searchRecipient.model.RecipientIndexRecord;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;

class RecipientResolverServiceTest {
    private RecipientSearchIndex index;
    private RecipientResolverService resolver;

    @BeforeEach void setup() {
        RecipientCatalog catalog = TestIndexFactory.catalog();
        index = TestIndexFactory.load();
        resolver = TestIndexFactory.resolver(new IndexRecipientDirectory(index, CatalogEngine.of(catalog)));
    }

    private ResolveRecipientResponse resolve(String query) {
        return resolver.resolve(new ResolveRecipientRequest(query, 200, 0));
    }

    private static Set<String> confirmedUsernames(ResolveRecipientResponse response) {
        return response.member_matches().stream().filter(m -> RecipientMatcher.CONFIRMED.equals(m.status()))
                .map(MemberMatch::list_username).collect(Collectors.toSet());
    }

    /** Independent expectation computed straight from the raw usernames with a regex. */
    private Set<String> usernamesWith(String designationPattern, Set<String> locationCodes) {
        Pattern designation = Pattern.compile(designationPattern);
        return index.snapshot().members().stream().map(RecipientIndexRecord::username).filter(username -> {
            List<String> tokens = List.of(username.split("@")[0].toLowerCase().split("[^a-z0-9]+"));
            return tokens.stream().anyMatch(t -> designation.matcher(t).matches())
                    && tokens.stream().anyMatch(locationCodes::contains);
        }).collect(Collectors.toSet());
    }

    @Test void loadsTheShippedIndexWithTheCsvRecordCounts() {
        assertEquals(3147, index.snapshot().members().size());
        assertEquals(216, index.snapshot().subgroups().size());
    }

    @Test void sisInNalgondaReturnsExactlyTheSubInspectorsOfTheDistrict() {
        ResolveRecipientResponse response = resolve("Send to all SIs in Nalgonda");
        assertEquals(usernamesWith("w?si\\d*", Set.of("nlg", "nalgonda")), confirmedUsernames(response));
        assertFalse(confirmedUsernames(response).contains("si-ahtu-mlg@suraksha"), "location hierarchy is disabled");
        assertFalse(response.needs_clarification());
    }

    @Test void circleInspectorsAndDspsInNalgondaHaveNoNoise() {
        assertEquals(usernamesWith("ci\\d*", Set.of("nlg", "nalgonda")), confirmedUsernames(resolve("Nalgonda Circle Inspectors")));
        assertEquals(usernamesWith("dsp\\d*", Set.of("nlg", "nalgonda")), confirmedUsernames(resolve("DSPs in Nalgonda")));
    }

    @Test void everyConfirmedMemberSatisfiesEveryFilter() {
        ResolveRecipientResponse response = resolve("Send to Cyberabad SHOs");
        assertFalse(response.member_matches().isEmpty());
        response.member_matches().stream().filter(m -> RecipientMatcher.CONFIRMED.equals(m.status())).forEach(m -> {
            assertTrue(m.matched_terms().contains("Station House Officer"), m.list_username());
            assertTrue(m.matched_terms().contains("Cyberabad"), m.list_username());
        });
    }

    @Test void exactMemberAndSubgroupNamesResolveToTheirIndexedIds() {
        RecipientIndexRecord member = index.snapshot().members().get(0);
        var memberResult = resolve(member.username());
        assertTrue(memberResult.member_matches().stream().anyMatch(m -> m.id() == member.id()
                && m.match_type().equals("EXACT_MATCH")));

        RecipientIndexRecord subgroup = index.snapshot().subgroups().stream()
                .filter(r -> r.name().equals("ALLSDPOs-NLG")).findFirst().orElseThrow();
        var groupResult = resolve(subgroup.name());
        assertTrue(groupResult.subgroup_matches().stream().anyMatch(m -> m.id() == subgroup.id()
                && m.match_type().equals("EXACT_MATCH")));
    }

    @Test void locationOnlyQueryUsesTheVerifiedCode() {
        var result = resolve("Find all officers in MLG");
        assertFalse(result.member_matches().isEmpty());
        assertTrue(result.member_matches().stream().allMatch(m -> m.matched_terms().contains("Miryalaguda")));
    }

    @Test void groupRequestsReturnOnlyGroups() {
        var result = resolve("select all groups in Nalgonda");
        assertEquals(Intent.RecipientType.GROUPS, result.recipient_type());
        assertTrue(result.member_matches().isEmpty());
        assertTrue(result.subgroup_matches().stream().anyMatch(g -> g.list_subgroup_name().equals("ALLCIs-NLG")));
    }

    @Test void misspelledLocationAsksInsteadOfGuessing() {
        var result = resolve("SIs in Nalgoda");
        assertTrue(result.needs_clarification());
        assertTrue(result.clarification().contains("Nalgonda"));
        assertTrue(result.member_matches().stream().noneMatch(m -> RecipientMatcher.CONFIRMED.equals(m.status())));
    }

    @Test void unknownQueryReturnsNoInventedIds() {
        var result = resolve("zzxxyyqwert");
        assertTrue(result.member_matches().isEmpty());
        assertTrue(result.subgroup_matches().isEmpty());
        assertTrue(result.needs_clarification());
        assertEquals(List.of("zzxxyyqwert"), result.interpretation().unresolved_terms());
    }

    @Test void queryWithOnlyCommandWordsAsksWhoToSendTo() {
        var result = resolve("send it to everyone please");
        assertTrue(result.needs_clarification());
        assertNotNull(result.clarification());
    }

    @Test void requestedListIdIsPassedToTheDirectory() {
        CatalogEngine engine = CatalogEngine.of(TestIndexFactory.catalog());
        RecipientDirectory directory = mock(RecipientDirectory.class);
        when(directory.snapshot(120L)).thenReturn(RecipientDirectory.Snapshot.of(120L, engine,
                List.of(engine.parser().member(9, "si-x-nlg@suraksha")), List.of()));
        var response = TestIndexFactory.resolver(directory)
                .resolve(new ResolveRecipientRequest("SIs in Nalgonda", 50, 0, 120L));
        assertEquals(9, response.member_matches().get(0).id());
        verify(directory).snapshot(120L);
    }

    @Test void confirmedResultsAreRankedBeforeSuggestions() {
        var result = resolve("SHOs in Karimnagar");
        List<String> statuses = result.member_matches().stream().map(MemberMatch::status).toList();
        int firstSuggestion = statuses.indexOf(RecipientMatcher.SUGGESTED);
        assertTrue(firstSuggestion > 0, "both kinds are expected for Karimnagar (KMR verified, KNR unverified)");
        assertTrue(statuses.subList(firstSuggestion, statuses.size()).stream().allMatch(RecipientMatcher.SUGGESTED::equals));
        assertNull(result.clarification());
    }

    @Test void audioTranscriptIsSentThroughTheSameResolver() throws Exception {
        SonioxTranscriptionService soniox = mock(SonioxTranscriptionService.class);
        when(soniox.transcribe(any(byte[].class), anyString(), any())).thenReturn(
                new SonioxTranscriptionService.Transcript("transcript-1", "Send to all SIs in Nalgonda"));
        RecipientController controller = new RecipientController(resolver, soniox, index);
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[]{1, 2, 3});

        var response = controller.resolveAudio(audio, List.of("en"), 50, 0);
        assertEquals("Send to all SIs in Nalgonda", response.transcript());
        assertEquals("transcript-1", response.soniox_transcription_id());
        assertTrue(response.recipient_resolution().member_matches().stream()
                .anyMatch(m -> m.matched_terms().containsAll(List.of("Sub-Inspector", "Nalgonda"))));
        verify(soniox).transcribe(any(byte[].class), eq("voice.wav"), eq(List.of("en")));
    }
}
