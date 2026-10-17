package com.example.recipient.searchRecipient.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.recipient.searchRecipient.RecipientMysqlRepository;
import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.MemberMatch;
import com.example.recipient.searchRecipient.model.RecipientIndexRecord;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.example.recipient.searchRecipient.model.SubgroupMatch;

@Service
public class RecipientResolverService {
    private static final Logger log = LoggerFactory.getLogger(RecipientResolverService.class);
    private final IntentParser parser;
    private final RecipientSearchIndex directIndex;
    private final ObjectProvider<RecipientSearchIndex> indexProvider;
    private final RecipientMysqlRepository mysqlRepository;
    private final MatchingService matching;
    private final int maxResults;
    private final String source;

    @Autowired
    public RecipientResolverService(IntentParser parser,
                                    ObjectProvider<RecipientSearchIndex> indexProvider,
                                    ObjectProvider<RecipientMysqlRepository> mysqlRepositoryProvider,
                                    MatchingService matching,
                                    @Value("${resolver.max-results:200}") int maxResults,
                                    @Value("${resolver.source:index}") String source) {
        this.parser = parser;
        this.directIndex = null;
        this.indexProvider = indexProvider;
        this.mysqlRepository = mysqlRepositoryProvider.getIfAvailable();
        this.matching = matching;
        this.maxResults = maxResults;
        this.source = source.trim().toLowerCase(Locale.ROOT);
    }

    /** Retained for direct construction in resolver unit tests. */
    public RecipientResolverService(IntentParser parser, RecipientSearchIndex index,
                                    MatchingService matching, int maxResults) {
        this.parser = parser;
        this.directIndex = index;
        this.indexProvider = null;
        this.mysqlRepository = null;
        this.matching = matching;
        this.maxResults = maxResults;
        this.source = "index";
    }

    public ResolveRecipientResponse resolve(ResolveRecipientRequest request) {
        String query = request.query().trim();
        return resolve(request, parser.parse(query));
    }

    public ResolveRecipientResponse resolve(ResolveRecipientRequest request, Intent intent) {
        String query = request.query().trim();

        log.info("resolve.request original_query=\"{}\" source={} list_id={} limit={} offset={}",
                query, source, request.listId(), request.effectiveLimit(), request.effectiveOffset());

        log.info("resolve.intent.complete original_query=\"{}\" concepts={} scope={} recipient_type={}",
                intent.originalQuery(), intent.concepts(), intent.allScope() ? "ALL" : "SOME", intent.recipientType());


        if (intent.concepts().isEmpty()) {
            log.info("resolve.complete reason=no_search_concepts member_matches=0 subgroup_matches=0");
            return new ResolveRecipientResponse(request.query(), List.of(), List.of(), true, false, null,
                    intent.recipientType());
        }

        List<MemberMatch> memberMatches = new ArrayList<>();
        List<SubgroupMatch> subgroupMatches = new ArrayList<>();

        if ("mysql".equals(source)) {

            if (mysqlRepository == null) throw new IllegalStateException("MySQL source is selected but its repository is unavailable");
            RecipientMysqlRepository.ScanResult memberScan = wantsMembers(intent)
                    ? mysqlRepository.searchMembers(intent, Long.valueOf(173), record -> addMemberMatch(record, intent, memberMatches))
                    : new RecipientMysqlRepository.ScanResult(0, false);
            RecipientMysqlRepository.ScanResult subgroupScan = wantsGroups(intent)
                    ? mysqlRepository.searchSubgroups(intent, Long.valueOf(173), record -> addSubgroupMatch(record, intent, subgroupMatches))
                    : new RecipientMysqlRepository.ScanResult(0, false);


            log.info("resolve.mysql.search.complete member_rows={} member_full_scan_fallback={} "
                            + "subgroup_rows={} subgroup_full_scan_fallback={}",
                    memberScan.rowsRead(), memberScan.fullScanFallback(),
                    subgroupScan.rowsRead(), subgroupScan.fullScanFallback());

                    
        } else {
            RecipientSearchIndex.Snapshot snapshot = currentIndex().snapshot();
            log.info("resolve.index.snapshot members={} subgroups={}", snapshot.members().size(), snapshot.subgroups().size());
            List<RecipientIndexRecord> memberCandidates = wantsMembers(intent)
                    ? candidates(intent, snapshot.memberByToken(), snapshot.members()) : List.of();
            List<RecipientIndexRecord> subgroupCandidates = wantsGroups(intent)
                    ? candidates(intent, snapshot.subgroupByToken(), snapshot.subgroups()) : List.of();
            log.info("resolve.index.candidates members={} subgroups={}", memberCandidates.size(), subgroupCandidates.size());
            memberCandidates.forEach(record -> addMemberMatch(record, intent, memberMatches));
            subgroupCandidates.forEach(record -> addSubgroupMatch(record, intent, subgroupMatches));
        }


        System.out.println("Member matches: " + memberMatches.size());
        memberMatches.sort(Comparator.comparingDouble(MemberMatch::score).reversed().thenComparingLong(MemberMatch::id));
        subgroupMatches.sort(Comparator.comparingDouble(SubgroupMatch::score).reversed().thenComparingLong(SubgroupMatch::id));

        int limit = Math.min(request.effectiveLimit(), maxResults);
        int offset = request.effectiveOffset();


        boolean more = memberMatches.size() > offset + limit || subgroupMatches.size() > offset + limit;
        List<MemberMatch> memberPage = page(memberMatches, offset, limit);
        List<SubgroupMatch> subgroupPage = page(subgroupMatches, offset, limit);
        boolean ambiguity = memberMatches.isEmpty() && subgroupMatches.isEmpty()
                || memberMatches.stream().anyMatch(m -> "POSSIBLE_MATCH".equals(m.match_type()))
                || subgroupMatches.stream().anyMatch(m -> "POSSIBLE_MATCH".equals(m.match_type()))
                || subgroupMatches.size() > 1 && subgroupMatches.get(0).score() - subgroupMatches.get(1).score() <= 0.08;
        double topMemberScore = memberMatches.isEmpty() ? 0.0 : memberMatches.get(0).score();
        double topSubgroupScore = subgroupMatches.isEmpty() ? 0.0 : subgroupMatches.get(0).score();


        log.info("resolve.ranking.complete member_matches={} top_member_score={} subgroup_matches={} top_subgroup_score={} needs_clarification={} has_more={}",
                memberMatches.size(), topMemberScore, subgroupMatches.size(), topSubgroupScore, ambiguity, more);
        log.info("resolve.results members={} subgroups={}", memberPage, subgroupPage);

        return new ResolveRecipientResponse(request.query(), memberPage, subgroupPage, ambiguity, more,
                more ? offset + limit : null, intent.recipientType());
    }

    public RecipientSearchIndex.IndexStatus reloadSource() {
        if ("mysql".equals(source)) {
            if (mysqlRepository == null) throw new IllegalStateException("MySQL source is selected but its repository is unavailable");
            return mysqlRepository.currentStatus();
        }
        return currentIndex().reload();
    }

    private RecipientSearchIndex currentIndex() {
        if (directIndex != null) return directIndex;
        RecipientSearchIndex index = indexProvider == null ? null : indexProvider.getIfAvailable();
        if (index == null) throw new IllegalStateException("JSON index source is selected but its index is unavailable");
        return index;
    }

    private void addMemberMatch(RecipientIndexRecord record, Intent intent, List<MemberMatch> matches) {
        MatchingService.Ranked rank = matching.rank(record, intent);
        if (rank != null) matches.add(new MemberMatch(record.id(), record.username(), rank.type(),
                rank.terms(), rank.reason(), rank.score()));
    }

    private void addSubgroupMatch(RecipientIndexRecord record, Intent intent, List<SubgroupMatch> matches) {
        MatchingService.Ranked rank = matching.rank(record, intent);
        if (rank != null) matches.add(new SubgroupMatch(record.id(), record.name(), record.description(),
                rank.type(), rank.terms(), rank.reason(), rank.score()));
    }

    private static boolean wantsMembers(Intent intent) {
        return intent.recipientType() != Intent.RecipientType.GROUPS;
    }

    private static boolean wantsGroups(Intent intent) {
        return intent.recipientType() != Intent.RecipientType.MEMBERS;
    }

    private static List<RecipientIndexRecord> candidates(Intent intent,
            Map<String, List<RecipientIndexRecord>> byToken, List<RecipientIndexRecord> allRecords) {
        Map<Long, RecipientIndexRecord> found = new LinkedHashMap<>();
        for (Intent.Concept concept : intent.concepts()) {
            for (String alternative : concept.alternatives()) {
                for (String token : AliasService.tokens(alternative)) {
                    for (RecipientIndexRecord row : byToken.getOrDefault(token, List.of())) found.putIfAbsent(row.id(), row);
                }
            }
        }
        // Small, in-memory corpus fallback enables local fuzzy ranking when no token hit exists.
        return found.isEmpty() ? allRecords : List.copyOf(found.values());
    }

    private static <T> List<T> page(List<T> values, int offset, int limit) {
        if (offset >= values.size()) return List.of();
        return values.subList(offset, Math.min(values.size(), offset + limit));
    }
}
