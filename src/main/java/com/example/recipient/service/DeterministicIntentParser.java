package com.example.recipient.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.example.recipient.model.Intent;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "none", matchIfMissing = true)
public class DeterministicIntentParser implements IntentParser {
    private final AliasService aliases;
    public DeterministicIntentParser(AliasService aliases) { this.aliases = aliases; }
    @Override 
    public Intent parse(String query) {
        String normalized = query.toLowerCase(java.util.Locale.ROOT);
        boolean allScope = normalized.matches(".*\\b(all|every|each)\\b.*");
        return new Intent(query, aliases.concepts(query),
                allScope, allScope ? Intent.RecipientType.BOTH : Intent.RecipientType.MEMBERS);
    }
}
