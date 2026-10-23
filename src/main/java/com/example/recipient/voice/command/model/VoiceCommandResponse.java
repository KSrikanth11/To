package com.example.recipient.voice.command.model;

import java.util.List;

import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoiceCommandResponse(String transcript, ComposeFormState form,
                                   ResolveRecipientResponse recipientResolution,
                                   List<UiAction> actions,
                                   VoiceCommandContext context) {
    /** Retained for callers that construct the earlier response shape. */
    public VoiceCommandResponse(String transcript, List<UiAction> actions) {
        this(transcript, null, null, actions, null);
    }

    public VoiceCommandResponse(String transcript, ComposeFormState form,
                                ResolveRecipientResponse recipientResolution, List<UiAction> actions) {
        this(transcript, form, recipientResolution, actions, null);
    }
}
