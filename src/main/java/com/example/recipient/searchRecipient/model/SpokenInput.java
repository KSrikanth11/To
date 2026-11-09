package com.example.recipient.searchRecipient.model;

/**
 * What the user actually said, alongside the English transcript Soniox produced.
 * The intent LLM reads both so a mistranslated name, rank or "except" can be caught.
 *
 * @param original the words as spoken, in the speaker's language
 * @param language language Soniox identified for most of the speech ("unknown" if none)
 */
public record SpokenInput(String original, String language) {
    public SpokenInput {
        original = original == null ? "" : original.trim();
        language = language == null || language.isBlank() ? "unknown" : language.trim();
    }

    /** True when the original speech adds something the English transcript does not already show. */
    public boolean addsTo(String english) {
        return !original.isEmpty() && !original.equalsIgnoreCase(english == null ? "" : english.trim());
    }
}
