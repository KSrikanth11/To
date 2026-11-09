package com.example.recipient.searchRecipient.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.MemberMatch;
import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.example.recipient.searchRecipient.model.SpokenInput;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse.Interpretation;
import com.example.recipient.searchRecipient.model.SubgroupMatch;

@Service
public class RecipientResolverService {
    private static final Logger log = LoggerFactory.getLogger(RecipientResolverService.class);
    private static final Comparator<MemberMatch> MEMBER_ORDER = Comparator
            .comparing((MemberMatch m) -> !RecipientMatcher.CONFIRMED.equals(m.status()))
            .thenComparing(Comparator.comparingDouble(MemberMatch::score).reversed())
            .thenComparing(MemberMatch::list_username);
    private static final Comparator<SubgroupMatch> SUBGROUP_ORDER = Comparator
            .comparing((SubgroupMatch m) -> !RecipientMatcher.CONFIRMED.equals(m.status()))
            .thenComparing(Comparator.comparingDouble(SubgroupMatch::score).reversed())
            .thenComparing(SubgroupMatch::list_subgroup_name);

    private final IntentParser parser;
    private final RecipientDirectory directory;
    private final int maxResults;

    @Autowired
    public RecipientResolverService(IntentParser parser, RecipientDirectory directory,
                                    @Value("${resolver.max-results:200}") int maxResults) {
        this.parser = parser;
        this.directory = directory;
        this.maxResults = maxResults;
    }

    public ResolveRecipientResponse resolve(ResolveRecipientRequest request) {
        return resolve(request, parser.parse(request.query().trim()));
    }

    /** Resolves a spoken request; the intent parser sees both the English transcript and the original speech. */
    public ResolveRecipientResponse resolve(ResolveRecipientRequest request, SpokenInput spoken) {
        return resolve(request, parser.parse(request.query().trim(), spoken));
    }

    public ResolveRecipientResponse resolve(ResolveRecipientRequest request, Intent intent) {
        // The list's own catalog reads the query, so terms mean what they mean for that tenant.
        RecipientDirectory.View list = directory.snapshot(request.listId());
        CatalogEngine engine = list.engine();
        RecipientMatcher matcher = engine.matcher();
        RecipientQuery interpreted = engine.interpreter().interpret(intent);
        if (!interpreted.hasFilters() && interpreted.exactKey().isEmpty()) {
            log.info("resolve.complete list_id={} reason=no_search_filters", list.listId());
            return new ResolveRecipientResponse(request.query(), List.of(), List.of(), true,
                    "Say who should receive this: a designation, a location, a unit, or a name.",
                    false, null, interpreted.recipientType(), 0, 0, Interpretation.empty());
        }

        RecipientQuery query = matcher.prepare(interpreted, list.words());

        List<MemberMatch> members = new ArrayList<>();
        if (query.recipientType() != Intent.RecipientType.GROUPS) {
            for (ParsedRecipient record : list.memberCandidates(query)) {
                RecipientMatcher.Ranked rank = matcher.match(record, query);
                if (rank != null) members.add(new MemberMatch(record.id(), record.name(), rank.status(), rank.type(),
                        rank.terms(), rank.reason(), rank.score()));
            }
        }
        List<SubgroupMatch> subgroups = new ArrayList<>();
        if (query.recipientType() != Intent.RecipientType.MEMBERS) {
            for (ParsedRecipient record : list.subgroupCandidates(query)) {
                RecipientMatcher.Ranked rank = matcher.match(record, query);
                if (rank != null) subgroups.add(new SubgroupMatch(record.id(), record.name(), record.description(),
                        rank.status(), rank.type(), rank.terms(), rank.reason(), rank.score()));
            }
        }
        // Groups are named by place far more often than by rank: "SI groups in Nalgonda" can find no group that
        // names SIs. Rather than an empty answer, offer that place's groups as suggestions (never auto-selected).
        String placeOnlyNote = null;
        if (subgroups.isEmpty() && query.recipientType() == Intent.RecipientType.GROUPS && query.unresolved().isEmpty()
                && !query.designations().isEmpty() && !query.locations().isEmpty()) {
            RecipientQuery placeOnly = new RecipientQuery(java.util.Set.of(), query.locations(), query.expandedLocations(),
                    query.keywords(), query.excludedDesignations(), query.excludedLocations(), query.excludedKeywords(),
                    query.inferred(), query.unresolved(), query.allScope(), query.recipientType(), query.exactKey());
            String ranks = query.designations().stream().map(engine.catalog()::label).sorted().collect(Collectors.joining(" or "));
            String places = query.locations().stream().sorted().collect(Collectors.joining(" or "));
            for (ParsedRecipient record : list.subgroupCandidates(placeOnly)) {
                RecipientMatcher.Ranked rank = matcher.match(record, placeOnly);
                if (rank != null) subgroups.add(new SubgroupMatch(record.id(), record.name(), record.description(),
                        RecipientMatcher.SUGGESTED, rank.type(), rank.terms(),
                        "No group is named for " + ranks + "; this group covers " + places + ".", rank.score()));
            }
            if (!subgroups.isEmpty()) placeOnlyNote = "No group is named for " + ranks + " in " + places
                    + ". These are the groups for " + places + ": choose the right one, or ask for " + ranks
                    + " members instead.";
            log.info("resolve.subgroup_place_fallback list_id={} suggested={}", list.listId(), subgroups.size());
        }
        members.sort(MEMBER_ORDER);
        subgroups.sort(SUBGROUP_ORDER);

        int limit = Math.min(request.effectiveLimit(), maxResults);
        int offset = request.effectiveOffset();
        boolean more = members.size() > offset + limit || subgroups.size() > offset + limit;
        long confirmed = members.stream().filter(m -> RecipientMatcher.CONFIRMED.equals(m.status())).count()
                + subgroups.stream().filter(m -> RecipientMatcher.CONFIRMED.equals(m.status())).count();
        String clarification = placeOnlyNote != null ? placeOnlyNote
                : clarification(query, confirmed, members.size() + subgroups.size());

        log.info("resolve.complete list_id={} designations={} locations={} keywords={} unresolved={} "
                        + "member_matches={} subgroup_matches={} confirmed={} has_more={}",
                list.listId(), query.designations().size(), query.locations().size(), query.keywords().size(),
                query.unresolved().size(), members.size(), subgroups.size(), confirmed, more);

        return new ResolveRecipientResponse(request.query(), page(members, offset, limit), page(subgroups, offset, limit),
                clarification != null, clarification, more, more ? offset + limit : null, query.recipientType(),
                members.size(), subgroups.size(), interpretation(query, engine.catalog()));
    }

    public RecipientDirectory.Status reloadSource() { return directory.reload(); }

    private static String clarification(RecipientQuery query, long confirmed, int total) {
        if (!query.unresolved().isEmpty()) {
            return query.unresolved().stream().map(term -> term.ambiguous()
                    ? "'" + term.term() + "' can mean " + String.join(" or ", term.suggestions()) + ". Which one did you mean?"
                    : term.suggestions().isEmpty()
                    ? "I could not find anyone matching '" + term.term() + "'."
                    : "I could not find '" + term.term() + "'. Did you mean " + String.join(" or ", term.suggestions()) + "?")
                    .collect(Collectors.joining(" "));
        }
        if (total == 0) return "No recipients match all of the requested filters.";
        if (confirmed == 0) return "Only possible matches were found. Please confirm them before sending.";
        return null;
    }

    private static Interpretation interpretation(RecipientQuery query, RecipientCatalog catalog) {
        List<String> excluded = new ArrayList<>();
        query.excludedDesignations().forEach(code -> excluded.add(catalog.label(code)));
        excluded.addAll(query.excludedLocations());
        excluded.addAll(query.excludedKeywords());
        return new Interpretation(
                query.designations().stream().map(catalog::label).sorted().toList(),
                query.locations().stream().sorted().toList(),
                query.keywords().stream().map(RecipientQuery.Keyword::term).toList(),
                List.copyOf(excluded),
                query.unresolved().stream().map(RecipientQuery.UnresolvedTerm::term).toList());
    }

    private static <T> List<T> page(List<T> values, int offset, int limit) {
        if (offset >= values.size()) return List.of();
        return List.copyOf(values.subList(offset, Math.min(values.size(), offset + limit)));
    }
}
