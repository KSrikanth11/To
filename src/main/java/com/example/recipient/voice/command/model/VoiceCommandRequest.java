package com.example.recipient.voice.command.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoiceCommandRequest(
        @NotBlank @Size(max = 500) String transcript,
        @Valid ComposeFormState form,
        @Valid VoiceCommandContext context) {

    public VoiceCommandRequest(String transcript, ComposeFormState form) {
        this(transcript, form, VoiceCommandContext.empty());
    }
}
