package com.example.recipient.voice.command.model;

import java.util.List;

import com.example.recipient.searchRecipient.model.Intent;

public record CommandIntent(
        IntentType intent,
        List<String> searchTerms,
        Intent.RecipientType recipientType,
        String scope,
        String text,
        List<String> labels,
        String field,
        double confidence) {

    public enum IntentType {
        SEARCH_RECIPIENTS, SET_MESSAGE, APPEND_MESSAGE, SET_SUBJECT,
        ADD_LABEL, REMOVE_LABEL, CLEAR_FIELD, SEND, UNKNOWN
    }
}
