package com.example.recipient.service;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.controller.RecipientController;
import com.example.recipient.model.ResolveRecipientRequest;
import com.example.recipient.model.RecipientIndexRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecipientResolverServiceTest {
    private RecipientSearchIndex index;
    private AliasService aliases;
    private RecipientResolverService resolver;

    @BeforeEach void setup() {
        index = TestIndexFactory.load();
        aliases = new AliasService("", index);
        resolver = new RecipientResolverService(new DeterministicIntentParser(aliases), index,
                new MatchingService(0.58), 200);
    }

    @Test void loadsTheShippedIndexWithTheCsvRecordCounts() {
        assertEquals(3147, index.snapshot().members().size());
        assertEquals(216, index.snapshot().subgroups().size());
    }

    @Test void exactMemberAndSubgroupNamesResolveToTheirIndexedIds() {
        RecipientIndexRecord member = index.snapshot().members().get(0);
        var memberResult = resolver.resolve(new ResolveRecipientRequest(member.username(), 50, 0));
        assertTrue(memberResult.member_matches().stream().anyMatch(m -> m.id() == member.id()
                && m.list_username().equals(member.username()) && m.match_type().equals("EXACT_MATCH")));

        RecipientIndexRecord subgroup = index.snapshot().subgroups().stream()
                .filter(r -> AliasService.tokens(r.name()).stream().anyMatch(t -> t.length() >= 3
                        && !List.of("the", "all", "and", "for", "of").contains(t)))
                .findFirst().orElseThrow();
        var groupResult = resolver.resolve(new ResolveRecipientRequest(subgroup.name(), 50, 0));
        assertTrue(groupResult.subgroup_matches().stream().anyMatch(m -> m.id() == subgroup.id()
                && m.list_subgroup_name().equals(subgroup.name())));
    }

    @Test void verifiedNlgAbbreviationExpandsToNalgonda() {
        var result = resolver.resolve(new ResolveRecipientRequest("NLG", 50, 0));
        assertTrue(result.member_matches().stream().anyMatch(m -> m.matched_terms().contains("Nalgonda"))
                || result.subgroup_matches().stream().anyMatch(m -> m.matched_terms().contains("Nalgonda")));
    }

    @Test void verifiedMlgAliasResolvesMiryalagudaQueries() {
        var result = resolver.resolve(new ResolveRecipientRequest("Find all officers in MLG", 200, 0));
        assertTrue(result.member_matches().stream().anyMatch(m -> m.matched_terms().contains("Miryalaguda"))
                || result.subgroup_matches().stream().anyMatch(m -> m.matched_terms().contains("Miryalaguda")));
    }

    @Test void cyberabadShoQueryUsesIndexedRoleTokensAndLocation() {
        var result = resolver.resolve(new ResolveRecipientRequest("Send to Cyberabad SHOs", 200, 0));
        assertTrue(result.member_matches().stream().anyMatch(m -> m.matched_terms().containsAll(List.of("Cyberabad", "Station House Officer")))
                || result.subgroup_matches().stream().anyMatch(m -> m.matched_terms().containsAll(List.of("Cyberabad", "Station House Officer"))));
    }

    @Test void rolePlusLocationUsesBothConcepts() {
        var result = resolver.resolve(new ResolveRecipientRequest("Send to all SIs in Nalgonda", 200, 0));
        assertTrue(result.member_matches().stream().anyMatch(m -> m.matched_terms().containsAll(List.of("Nalgonda", "Sub-Inspector")))
                || result.subgroup_matches().stream().anyMatch(m -> m.matched_terms().containsAll(List.of("Nalgonda", "Sub-Inspector"))));
    }

    @Test void memberOnlySearchUsesTheMemberIndex() {
        String token = index.snapshot().members().stream().flatMap(r -> r.tokens().stream())
                .map(AliasService::normalize).filter(t -> t.length() >= 6)
                .filter(t -> !index.snapshot().subgroupByToken().containsKey(t)).findFirst().orElseThrow();
        var result = resolver.resolve(new ResolveRecipientRequest(token, 200, 0));
        assertFalse(result.member_matches().isEmpty());
        assertTrue(result.subgroup_matches().isEmpty());
    }

    @Test void subgroupOnlySearchUsesTheSubgroupIndex() {
        String token = index.snapshot().subgroups().stream().flatMap(r -> r.tokens().stream())
                .map(AliasService::normalize).filter(t -> t.length() >= 6)
                .filter(t -> !index.snapshot().memberByToken().containsKey(t)).findFirst().orElseThrow();
        var result = resolver.resolve(new ResolveRecipientRequest(token, 200, 0));
        assertFalse(result.subgroup_matches().isEmpty());
        assertTrue(result.member_matches().isEmpty());
    }

    @Test void unverifiedTermsAreNotExpandedAsAliases() {
        var concepts = aliases.concepts("Mulugu");
        assertEquals(1, concepts.size());
        assertEquals("mulugu", concepts.get(0).label());
        assertEquals(List.of("mulugu"), concepts.get(0).alternatives());
    }

    @Test void unknownQueryReturnsNoInventedIds() {
        var result = resolver.resolve(new ResolveRecipientRequest("zzxxyyqwert", 50, 0));
        assertTrue(result.member_matches().isEmpty());
        assertTrue(result.subgroup_matches().isEmpty());
        assertTrue(result.needs_clarification());
    }

    @Test void audioTranscriptIsSentThroughTheSameOfflineResolver() throws Exception {
        SonioxTranscriptionService soniox = mock(SonioxTranscriptionService.class);
        when(soniox.transcribe(any(byte[].class), anyString(), any())).thenReturn(
                new SonioxTranscriptionService.Transcript("transcript-1", "Send to all SIs in Nalgonda"));
        RecipientController controller = new RecipientController(resolver, soniox, index);
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", new byte[]{1, 2, 3});

        var response = controller.resolveAudio(audio, List.of("en"), 50, 0);
        assertEquals("Send to all SIs in Nalgonda", response.transcript());
        assertEquals("transcript-1", response.soniox_transcription_id());
        assertTrue(response.recipient_resolution().member_matches().stream()
                .anyMatch(m -> m.matched_terms().containsAll(List.of("Nalgonda", "Sub-Inspector"))));
        verify(soniox).transcribe(any(byte[].class), eq("voice.wav"), eq(List.of("en")));
    }
}
