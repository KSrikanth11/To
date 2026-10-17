package com.example.recipient.voice.command.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiAction(String type, String message, String field, String value, String mode,
                       List<Object> candidates) {

    public static UiAction notUnderstood(String message) {
        return new UiAction("NOT_UNDERSTOOD", message, null, null, null, null);
    }

    public static UiAction setField(String field, String value) {
        return new UiAction("SET_FIELD", null, field, value, "REPLACE", null);
    }

    public static UiAction setRecipients(List<Object> candidates) {
        return new UiAction("SET_RECIPIENTS", null, null, null, null, candidates);
    }
}
