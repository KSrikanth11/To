package com.example.recipient.searchRecipient;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.RecipientIndexRecord;
import com.example.recipient.searchRecipient.service.AliasService;
import com.example.recipient.searchRecipient.service.RecipientSearchIndex;

/** Streams live MySQL rows into the matcher; it does not cache rows or build a token index. */
@Repository
@ConditionalOnProperty(name = "resolver.source", havingValue = "mysql")
public class RecipientMysqlRepository {
    private static final Logger log = LoggerFactory.getLogger(RecipientMysqlRepository.class);
    private static final String MEMBER_SQL =
            "SELECT id, list_username FROM list_members WHERE list_id = ? ORDER BY id";
    private static final String SUBGROUP_SQL =
            "SELECT id, sub_group_name, sub_group_desc FROM list_sub_groups WHERE list_id = ? ORDER BY id";

    private final JdbcTemplate jdbc;
    private final long listId;

    public RecipientMysqlRepository(JdbcTemplate jdbc,
                                    @Value("${resolver.mysql.list-id:173}") long listId) {
        this.jdbc = jdbc;
        this.listId = listId;
    }

    public ScanResult searchMembers(Intent intent, Long requestedListId, Consumer<RecipientIndexRecord> consumer) {
        long effectiveListId = requestedListId == null ? listId : requestedListId;
        System.out.println("List_id for Members search: " + effectiveListId + " Intent: " + intent);
        SearchPredicate predicate = searchPredicate("LOWER(COALESCE(list_username, ''))", intent);
        if (predicate == null) {
            logQuery("mysql.members.full_scan", MEMBER_SQL, new Object[]{effectiveListId});
            return new ScanResult(streamMembers(MEMBER_SQL, new Object[]{effectiveListId}, consumer), true);
        }
        String sql = "SELECT id, list_username FROM list_members WHERE list_id = ? AND "
                + predicate.sql() + " ORDER BY id";
        Object[] args = queryArguments(predicate, effectiveListId);
        logQuery("mysql.members.search", sql, args);
        long candidates = streamMembers(sql, args, consumer);
        if (candidates > 0) return new ScanResult(candidates, false);

        // Preserve the existing fuzzy fallback when no SQL token candidate exists.
        logQuery("mysql.members.full_scan_fallback", MEMBER_SQL, new Object[]{effectiveListId});
        return new ScanResult(streamMembers(MEMBER_SQL, new Object[]{effectiveListId}, consumer), true);
    }

    public ScanResult searchSubgroups(Intent intent, Long requestedListId, Consumer<RecipientIndexRecord> consumer) {
        long effectiveListId = requestedListId == null ? listId : requestedListId;
        String searchable = "LOWER(CONCAT_WS(' ', COALESCE(sub_group_name, ''), COALESCE(sub_group_desc, '')))";
        SearchPredicate predicate = searchPredicate(searchable, intent);
        if (predicate == null) {
            logQuery("mysql.subgroups.full_scan", SUBGROUP_SQL, new Object[]{effectiveListId});
            return new ScanResult(streamSubgroups(SUBGROUP_SQL, new Object[]{effectiveListId}, consumer), true);
        }
        String sql = "SELECT id, sub_group_name, sub_group_desc FROM list_sub_groups WHERE list_id = ? AND "
                + predicate.sql() + " ORDER BY id";
        Object[] args = queryArguments(predicate, effectiveListId);
        logQuery("mysql.subgroups.search", sql, args);
        long candidates = streamSubgroups(sql, args, consumer);
        if (candidates > 0) return new ScanResult(candidates, false);

        // Preserve the existing fuzzy fallback when no SQL token candidate exists.
        logQuery("mysql.subgroups.full_scan_fallback", SUBGROUP_SQL, new Object[]{effectiveListId});
        return new ScanResult(streamSubgroups(SUBGROUP_SQL, new Object[]{effectiveListId}, consumer), true);
    }

    private long streamMembers(String sql, Object[] args, Consumer<RecipientIndexRecord> consumer) {
        AtomicLong scanned = new AtomicLong();
        jdbc.query(sql, row -> {
            long id = row.getLong("id");
            if (row.wasNull()) throw new IllegalArgumentException("list_members.id cannot be null");
            String username = clean(row.getString("list_username"));
            List<String> tokens = AliasService.tokens(username);
            consumer.accept(new RecipientIndexRecord("member", id, username, username, "", username, tokens, tokens));
            scanned.incrementAndGet();
        }, args);
        return scanned.get();
    }

    private long streamSubgroups(String sql, Object[] args, Consumer<RecipientIndexRecord> consumer) {
        AtomicLong scanned = new AtomicLong();
        jdbc.query(sql, row -> {
            long id = row.getLong("id");
            if (row.wasNull()) throw new IllegalArgumentException("list_sub_groups.id cannot be null");
            String name = clean(row.getString("sub_group_name"));
            String description = clean(row.getString("sub_group_desc"));
            String searchText = (name + " " + description).trim();
            List<String> tokens = AliasService.tokens(searchText);
            consumer.accept(new RecipientIndexRecord("subgroup", id, name, "", description, searchText, tokens, tokens));
            scanned.incrementAndGet();
        }, args);
        return scanned.get();
    }

    /**
     * Push full concept coverage into SQL: alternatives within one concept are ORed,
     * while distinct query concepts are ANDed. Java still applies canonical alias,
     * fuzzy, score, and threshold rules to these candidate rows.
     */
    private static SearchPredicate searchPredicate(String columnExpression, Intent intent) {
        if (intent.concepts().isEmpty()) return null;
        List<String> conceptPredicates = new java.util.ArrayList<>();
        List<String> terms = new java.util.ArrayList<>();
        for (Intent.Concept concept : intent.concepts()) {
            List<String> alternatives = new java.util.ArrayList<>();
            for (String alternative : concept.alternatives()) {
                List<String> tokens = AliasService.tokens(alternative);
                if (tokens.isEmpty()) continue;
                List<String> tokenPredicates = new java.util.ArrayList<>();
                for (String token : tokens) {
                    tokenPredicates.add(columnExpression + " LIKE ? ESCAPE '!'");
                    terms.add(token);
                }
                alternatives.add("(" + String.join(" AND ", tokenPredicates) + ")");
            }
            if (alternatives.isEmpty()) return null;
            conceptPredicates.add("(" + String.join(" OR ", alternatives) + ")");
        }
        return new SearchPredicate(String.join(" AND ", conceptPredicates), List.copyOf(terms));
    }

    private Object[] queryArguments(SearchPredicate predicate, long effectiveListId) {
        Object[] args = new Object[predicate.terms().size() + 1];
        args[0] = effectiveListId;
        for (int i = 0; i < predicate.terms().size(); i++)
            args[i + 1] = "%" + escapeLike(predicate.terms().get(i)) + "%";
        return args;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public RecipientSearchIndex.IndexStatus currentStatus() {
        Long members = jdbc.queryForObject("SELECT COUNT(*) FROM list_members WHERE list_id = ?", Long.class, listId);
        Long subgroups = jdbc.queryForObject("SELECT COUNT(*) FROM list_sub_groups WHERE list_id = ?", Long.class, listId);
        return new RecipientSearchIndex.IndexStatus(
                Math.toIntExact(members == null ? 0L : members),
                Math.toIntExact(subgroups == null ? 0L : subgroups), Instant.now());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static void logQuery(String step, String sql, Object[] args) {
        log.info("{} sql={} parameters={}", step, sql, Arrays.toString(args));
    }

    private record SearchPredicate(String sql, List<String> terms) { }

    public record ScanResult(long rowsRead, boolean fullScanFallback) { }
}
