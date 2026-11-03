package com.example.recipient.searchRecipient.service;

import java.util.List;

import com.example.recipient.searchRecipient.model.Intent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;


/** Passes the whole query to the interpreter, which drops command and filler words itself. */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "none", matchIfMissing = true)
public class DeterministicIntentParser implements IntentParser {
    @Override
    public Intent parse(String query) {
        return parseQuery(query);
    }

    static Intent parseQuery(String query) {
        boolean allScope = query.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(all|every|each)\\b.*");
        return new Intent(query, List.of(query), List.of(), allScope, Intent.RecipientType.AUTO);
    }
}
