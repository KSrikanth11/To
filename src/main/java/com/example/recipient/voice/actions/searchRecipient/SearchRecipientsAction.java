package com.example.recipient.voice.actions.searchRecipient;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.MemberMatch;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.example.recipient.searchRecipient.model.SubgroupMatch;
import com.example.recipient.searchRecipient.service.RecipientMatcher;
import com.example.recipient.searchRecipient.service.RecipientResolverService;
import com.example.recipient.voice.command.model.CommandIntent;

@Component
public class SearchRecipientsAction {
    private final RecipientResolverService resolver;
    private final int maxResults;

    public SearchRecipientsAction(RecipientResolverService resolver,
                                  @Value("${resolver.max-results:200}") int maxResults) {
        this.resolver = resolver;
        this.maxResults = maxResults;
    }

    /** What to select automatically, what to offer, and what to ask. */
    public record Selection(List<Object> selected, List<Object> suggested, String clarification) { }

    public ResolveRecipientResponse resolve(String transcript, CommandIntent command) {
        Intent intent = new Intent(transcript, command.searchTerms(), command.excludeTerms(),
                "ALL".equals(command.scope()), command.recipientType());
        // Ask for every match at once: auto-selecting only the first page would silently drop recipients.
        return resolver.resolve(new ResolveRecipientRequest(transcript, maxResults, 0, null), intent);
    }

    /**
     * Only CONFIRMED matches are selected. Members are preferred over groups so nobody receives the
     * message twice; confirmed groups are then offered as an alternative instead of being added.
     */
    public Selection select(ResolveRecipientResponse resolution) {
        if (resolution.has_more()) {
            return new Selection(List.of(), List.of(), (resolution.member_total() + resolution.subgroup_total())
                    + " recipients match. Add a location, designation or unit to narrow this down.");
        }
        List<MemberMatch> confirmedMembers = resolution.member_matches().stream()
                .filter(m -> RecipientMatcher.CONFIRMED.equals(m.status())).toList();
        List<SubgroupMatch> confirmedGroups = resolution.subgroup_matches().stream()
                .filter(m -> RecipientMatcher.CONFIRMED.equals(m.status())).toList();

        List<Object> selected = new ArrayList<>();
        List<Object> suggested = new ArrayList<>();
        if (resolution.recipient_type() != Intent.RecipientType.GROUPS && !confirmedMembers.isEmpty()) {
            selected.addAll(confirmedMembers);
            suggested.addAll(confirmedGroups);
        } else {
            selected.addAll(confirmedGroups);
        }
        resolution.member_matches().stream().filter(m -> !RecipientMatcher.CONFIRMED.equals(m.status())).forEach(suggested::add);
        resolution.subgroup_matches().stream().filter(m -> !RecipientMatcher.CONFIRMED.equals(m.status())).forEach(suggested::add);
        return new Selection(List.copyOf(selected), List.copyOf(suggested), resolution.clarification());
    }
}
