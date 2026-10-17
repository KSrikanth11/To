package com.example.recipient.voice.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private SearchRecipientsAction searchRecipients;
    private SetMessageAction setMessage;
    private SetSubjectAction setSubject;
    private CommandRouter router;

    @BeforeEach
    void setUp() {
        extractor = mock(IntentExtractor.class);
        searchRecipients = mock(SearchRecipientsAction.class);
        setMessage = mock(SetMessageAction.class);
        setSubject = mock(SetSubjectAction.class);
        router = new CommandRouter(extractor, searchRecipients, setMessage, setSubject);
    }

    @Test
    void routesRecipientSearch() {
        CommandIntent intent = command(IntentType.SEARCH_RECIPIENTS, 0.9);
        UiAction action = UiAction.setRecipients(List.of());
        when(extractor.extract("find Ravi", ComposeFormState.empty())).thenReturn(intent);
        when(searchRecipients.apply("find Ravi", intent)).thenReturn(action);

        VoiceCommandResponse response = router.route("find Ravi", ComposeFormState.empty());

        assertEquals(List.of(action), response.actions());
        verify(searchRecipients).apply("find Ravi", intent);
    }

    @Test
    void routesSetMessage() {
        CommandIntent intent = command(IntentType.SET_MESSAGE, 0.9);
        UiAction action = UiAction.setField("MESSAGE", "hello");
        when(extractor.extract("write a message hello", ComposeFormState.empty())).thenReturn(intent);
        when(setMessage.apply(intent)).thenReturn(action);

        VoiceCommandResponse response = router.route("write a message hello", ComposeFormState.empty());

        assertEquals(List.of(action), response.actions());
        verify(setMessage).apply(intent);
    }

    @Test
    void routesSetSubject() {
        CommandIntent intent = command(IntentType.SET_SUBJECT, 0.9);
        UiAction action = UiAction.setField("SUBJECT", "Review");
        when(extractor.extract("subject is Review", ComposeFormState.empty())).thenReturn(intent);
        when(setSubject.apply(intent)).thenReturn(action);

        VoiceCommandResponse response = router.route("subject is Review", ComposeFormState.empty());

        assertEquals(List.of(action), response.actions());
        verify(setSubject).apply(intent);
    }

    @Test
    void returnsNotUnderstoodForUnknownOrLowConfidence() {
        when(extractor.extract("hmm", ComposeFormState.empty()))
                .thenReturn(command(IntentType.UNKNOWN, 0.9));

        VoiceCommandResponse response = router.route("hmm", ComposeFormState.empty());

        assertEquals("NOT_UNDERSTOOD", response.actions().get(0).type());
        assertEquals("Please repeat", response.actions().get(0).message());
    }

    @Test
    void returnsNotUnderstoodBelowConfidenceThreshold() {
        when(extractor.extract("maybe Ravi", ComposeFormState.empty()))
                .thenReturn(command(IntentType.SEARCH_RECIPIENTS, 0.59));

        VoiceCommandResponse response = router.route("maybe Ravi", ComposeFormState.empty());

        assertEquals("NOT_UNDERSTOOD", response.actions().get(0).type());
        assertEquals("Please repeat", response.actions().get(0).message());
    }

    private static CommandIntent command(IntentType type, double confidence) {
        return new CommandIntent(type, List.of(), Intent.RecipientType.MEMBERS,
                "SOME", "hello", List.of(), null, confidence);
    }
}
