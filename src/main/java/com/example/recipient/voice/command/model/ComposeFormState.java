package com.example.recipient.voice.command.model;

import java.util.List;

public record ComposeFormState(String subject, String message, List<Long> labels, int recipientCount) {
    public ComposeFormState {
        subject = subject == null ? "" : subject;
        message = message == null ? "" : message;
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    public static ComposeFormState empty() {
        return new ComposeFormState("", "", List.of(), 0);
    }
}
