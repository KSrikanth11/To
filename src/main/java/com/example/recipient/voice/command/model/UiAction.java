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

    /** Possible matches and alternative groups; show them unchecked, never select automatically. */
    public static UiAction suggestRecipients(List<Object> candidates) {
        return new UiAction("SUGGEST_RECIPIENTS", null, null, null, null, candidates);
    }

    /** Ask the user to settle an unclear recipient request before anything is selected. */
    public static UiAction clarifyRecipients(String message) {
        return new UiAction("CLARIFY_RECIPIENTS", message, "RECIPIENTS", null, null, null);
    }

    public static UiAction updateLabels(String operation, String label) {
        return new UiAction("UPDATE_LABELS", null, "LABELS", label, operation, null);
    }

    public static UiAction requestSendConfirmation() {
        return new UiAction("REQUEST_SEND_CONFIRMATION", "Confirm before sending", null, null, null, null);
    }
}
