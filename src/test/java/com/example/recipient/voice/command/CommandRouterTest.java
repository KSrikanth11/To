package com.example.recipient.voice.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.voice.actions.searchRecipient.SearchRecipientsAction;
import com.example.recipient.voice.actions.setmessage.SetMessageAction;
import com.example.recipient.voice.actions.setsubject.SetSubjectAction;
import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.CommandIntent.IntentType;
import com.example.recipient.voice.command.model.ComposeFormState;
import com.example.recipient.voice.command.model.UiAction;
import com.example.recipient.voice.command.model.VoiceCommandResponse;

import static org.mockito.Mockito.mock;

class CommandRouterTest {
    private IntentExtractor extractor;
    private SetMessageAction setMessage;
    private SetSubjectAction setSubject;
    private CommandRouter router;

    @BeforeEach
    void setUp() {
        extractor = mock(IntentExtractor.class);
        setMessage = mock(SetMessageAction.class);
        setSubject = mock(SetSubjectAction.class);
        SearchRecipientsAction searchRecipients = new SearchRecipientsAction(TestIndexFactory.resolver(), 200);
        router = new CommandRouter(extractor, searchRecipients, setMessage, setSubject);
    }

    private void extracts(String transcript, CommandIntent... commands) {
        when(extractor.extractCommands(any(), any(), any())).thenReturn(List.of(commands));
    }

    private static List<String> types(VoiceCommandResponse response) {
        return response.actions().stream().map(UiAction::type).toList();
    }

    @Test
    void selectsOnlyConfirmedMembersAndOffersGroupsInsteadOfDuplicating() {
        extracts("send to all DSPs in Nalgonda", search(List.of("DSPs", "Nalgonda"), List.of(), "ALL"));

        VoiceCommandResponse response = router.route("send to all DSPs in Nalgonda", ComposeFormState.empty());

        UiAction set = response.actions().get(0);
        assertEquals("SET_RECIPIENTS", set.type());
        assertEquals(5, set.candidates().size());
        assertTrue(set.candidates().stream().allMatch(c -> c instanceof com.example.recipient.searchRecipient.model.MemberMatch));
        assertEquals("SUGGEST_RECIPIENTS", response.actions().get(1).type());
        assertTrue(response.actions().get(1).candidates().stream()
                .anyMatch(c -> c.toString().contains("ALLDSPs-NLG")), "the matching group is offered, not added");
        assertEquals(5, response.form().recipientCount());
    }

    @Test
    void unclearRecipientsAreClarifiedAndNothingIsSelected() {
        extracts("send to SIs in Nalgoda", search(List.of("SIs", "Nalgoda"), List.of(), "ALL"));

        VoiceCommandResponse response = router.route("send to SIs in Nalgoda", ComposeFormState.empty());

        assertEquals(List.of("SUGGEST_RECIPIENTS", "CLARIFY_RECIPIENTS"), types(response));
        assertTrue(response.actions().get(1).message().contains("Nalgonda"));
        assertEquals(0, response.form().recipientCount());
    }

    @Test
    void tooManyMatchesAsksToNarrowInsteadOfSelectingAPage() {
        extracts("send to all SHOs", search(List.of("SHOs"), List.of(), "ALL"));

        VoiceCommandResponse response = router.route("send to all SHOs", ComposeFormState.empty());

        assertEquals(List.of("CLARIFY_RECIPIENTS"), types(response));
        assertTrue(response.actions().get(0).message().contains("narrow"));
    }

    @Test
    void routesSetMessage() {
        CommandIntent intent = command(IntentType.SET_MESSAGE, 0.9);
        UiAction action = UiAction.setField("MESSAGE", "hello");
        extracts("write a message hello", intent);
        when(setMessage.apply(intent)).thenReturn(action);

        VoiceCommandResponse response = router.route("write a message hello", ComposeFormState.empty());

        assertEquals(List.of(action), response.actions());
        verify(setMessage).apply(intent);
    }

    @Test
    void routesSetSubject() {
        CommandIntent intent = command(IntentType.SET_SUBJECT, 0.9);
        UiAction action = UiAction.setField("SUBJECT", "Review");
        extracts("subject is Review", intent);
        when(setSubject.apply(intent)).thenReturn(action);

        VoiceCommandResponse response = router.route("subject is Review", ComposeFormState.empty());

        assertEquals(List.of(action), response.actions());
        verify(setSubject).apply(intent);
    }

    @Test
    void returnsNotUnderstoodForUnknownOrLowConfidence() {
        extracts("hmm", command(IntentType.UNKNOWN, 0.9));

        VoiceCommandResponse response = router.route("hmm", ComposeFormState.empty());

        assertEquals("NOT_UNDERSTOOD", response.actions().get(0).type());
        assertEquals("Please repeat", response.actions().get(0).message());
    }

    @Test
    void returnsNotUnderstoodBelowConfidenceThreshold() {
        extracts("maybe Ravi", command(IntentType.SEARCH_RECIPIENTS, 0.59));

        VoiceCommandResponse response = router.route("maybe Ravi", ComposeFormState.empty());

        assertEquals("NOT_UNDERSTOOD", response.actions().get(0).type());
        assertEquals("Please repeat", response.actions().get(0).message());
    }

    private static CommandIntent search(List<String> terms, List<String> excluded, String scope) {
        return new CommandIntent(IntentType.SEARCH_RECIPIENTS, terms, excluded, Intent.RecipientType.AUTO,
                scope, null, List.of(), null, 0.95);
    }

    private static CommandIntent command(IntentType type, double confidence) {
        return new CommandIntent(type, List.of(), List.of(), Intent.RecipientType.MEMBERS,
                "SOME", "hello", List.of(), null, confidence);
    }

    @Test
    void spokenCommandGivesTheExtractorTheOriginalSpeechAndReturnsIt() {
        com.example.recipient.searchRecipient.model.SpokenInput spoken =
                new com.example.recipient.searchRecipient.model.SpokenInput("నల్గొండ డీఎస్పీలు అందరికీ పంపండి", "te");
        when(extractor.extractCommands(any(), any(), any(), any())).thenReturn(
                List.of(search(List.of("DSPs", "Nalgonda"), List.of(), "ALL")));

        VoiceCommandResponse response = router.route("Send to all DSPs in Nalgonda", ComposeFormState.empty(), null, spoken)
                .withSpeech(spoken.original(), spoken.language());

        verify(extractor).extractCommands(org.mockito.ArgumentMatchers.eq("Send to all DSPs in Nalgonda"), any(), any(),
                org.mockito.ArgumentMatchers.eq(spoken));
        assertEquals("SET_RECIPIENTS", response.actions().get(0).type());
        assertEquals("Send to all DSPs in Nalgonda", response.transcript());
        assertEquals(spoken.original(), response.originalTranscript());
        assertEquals("te", response.detectedLanguage());
    }
}
