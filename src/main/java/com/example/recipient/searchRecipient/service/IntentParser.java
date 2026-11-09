package com.example.recipient.searchRecipient.service;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.SpokenInput;

public interface IntentParser {
    Intent parse(String query);

    /**
     * Parses a spoken request: {@code english} is the speech-to-text translation and {@code spoken}
     * the original words. Parsers that cannot use the original speech read the English text only.
     */
    default Intent parse(String english, SpokenInput spoken) {
        return parse(english);
    }
}
