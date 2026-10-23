package com.example.recipient.voice.actions.searchRecipient;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.example.recipient.searchRecipient.service.AliasService;
import com.example.recipient.searchRecipient.service.RecipientResolverService;
import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.UiAction;

@Component
public class SearchRecipientsAction {
    private final AliasService aliases;
    private final RecipientResolverService resolver;

    public SearchRecipientsAction(AliasService aliases, RecipientResolverService resolver) {
        this.aliases = aliases;
        this.resolver = resolver;
    }

    public UiAction apply(String transcript, CommandIntent command) {
        ResolveRecipientResponse results = resolve(transcript, command);
        List<Object> candidates = new ArrayList<>();
        candidates.addAll(results.member_matches());
        candidates.addAll(results.subgroup_matches());
        return UiAction.setRecipients(candidates);
    }

    public ResolveRecipientResponse resolve(String transcript, CommandIntent command) {
        List<Intent.Concept> concepts = aliases.concepts(String.join(" ", command.searchTerms()));
        Intent intent = new Intent(transcript, concepts, "ALL".equals(command.scope()), command.recipientType());
        return resolver.resolve(new ResolveRecipientRequest(transcript, null, null), intent);
    }
}
