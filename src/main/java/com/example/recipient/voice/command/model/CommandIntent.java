package com.example.recipient.voice.command.model;

import java.util.List;

import com.example.recipient.searchRecipient.model.Intent;

public record CommandIntent(
        IntentType intent,
        List<String> searchTerms,
        List<String> excludeTerms,
        Intent.RecipientType recipientType,
        String scope,
        String text,
        List<String> labels,
        String field,
        double confidence) {

    public CommandIntent {
        searchTerms = searchTerms == null ? List.of() : List.copyOf(searchTerms);
        excludeTerms = excludeTerms == null ? List.of() : List.copyOf(excludeTerms);
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    public enum IntentType {
        SEARCH_RECIPIENTS, SET_MESSAGE, APPEND_MESSAGE, SET_SUBJECT,
        ADD_LABEL, REMOVE_LABEL, CLEAR_FIELD, SEND, UNKNOWN
    }
}
