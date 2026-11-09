package com.example.recipient.voice.command.model;

import java.util.List;

import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code transcript} is the English text the command was read from. For audio, {@code originalTranscript}
 * holds the words as spoken and {@code detectedLanguage} the language Soniox identified; both are omitted
 * for typed commands.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoiceCommandResponse(String transcript, ComposeFormState form,
                                   ResolveRecipientResponse recipientResolution,
                                   List<UiAction> actions,
                                   VoiceCommandContext context,
                                   String originalTranscript, String detectedLanguage) {
    /** Retained for callers that construct the earlier response shape. */
    public VoiceCommandResponse(String transcript, List<UiAction> actions) {
        this(transcript, null, null, actions, null);
    }

    public VoiceCommandResponse(String transcript, ComposeFormState form,
                                ResolveRecipientResponse recipientResolution, List<UiAction> actions) {
        this(transcript, form, recipientResolution, actions, null);
    }

    public VoiceCommandResponse(String transcript, ComposeFormState form,
                                ResolveRecipientResponse recipientResolution, List<UiAction> actions,
                                VoiceCommandContext context) {
        this(transcript, form, recipientResolution, actions, context, null, null);
    }

    public VoiceCommandResponse withSpeech(String spoken, String language) {
        return new VoiceCommandResponse(transcript, form, recipientResolution, actions, context, spoken, language);
    }
}
