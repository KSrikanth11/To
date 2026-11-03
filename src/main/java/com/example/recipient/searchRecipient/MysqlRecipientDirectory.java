package com.example.recipient.searchRecipient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.recipient.searchRecipient.model.MemberProfile;
import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.RecipientQuery;
import com.example.recipient.searchRecipient.service.CatalogEngine;
import com.example.recipient.searchRecipient.service.CatalogNotConfiguredException;
import com.example.recipient.searchRecipient.service.RecipientCatalog;
import com.example.recipient.searchRecipient.service.RecipientDirectory;
import com.example.recipient.searchRecipient.service.RecipientMatcher;
import com.example.recipient.searchRecipient.service.TextNormalizer;

/**
 * Reads one list from MySQL. Only its catalog (recipient_catalog_phrase, recipient_query_word) is cached, for a
 * short TTL. Members, their decoded profiles (recipient_member_profile/_attribute) and subgroups are queried on
 * every search: CandidateSql selects the rows that can match, and only those are parsed with the catalog.
 */
@Repository
@ConditionalOnProperty(name = "resolver.source", havingValue = "mysql")
public class MysqlRecipientDirectory implements RecipientDirectory {
    private static final Logger log = LoggerFactory.getLogger(MysqlRecipientDirectory.class);
    static final String PHRASE_SQL = "SELECT kind, catalog_key, label, alias, verified, parent_code FROM recipient_catalog_phrase "
            + "WHERE list_id = ? ORDER BY FIELD(kind, 'DESIGNATION', 'LOCATION', 'UNIT'), catalog_key, normalized_alias";
    static final String WORD_SQL = "SELECT word, category FROM recipient_query_word WHERE list_id = ? AND active = 1";
    // Candidates come from two parts: list_members scanned by username, and members with a decoded profile
    // (a small table) checked against their attributes and texts too. Only the candidates are joined to profiles.
    static final String MEMBER_SQL = "SELECT c.id, c.list_username, p.username AS profile_username, p.confidence, "
            + "p.designation_text, p.rank_text, p.wing_text, p.station_text, p.zone_text, p.district_text FROM ("
            + "SELECT m.id, m.list_username FROM list_members m WHERE m.list_id = ? AND (%s) "
            + "UNION SELECT m.id, m.list_username FROM recipient_member_profile p "
            + "JOIN list_members m ON m.list_id = p.list_id AND m.id = p.member_id WHERE p.list_id = ? AND p.active = 1 AND (%s)"
            + ") c LEFT JOIN recipient_member_profile p ON p.list_id = ? AND p.member_id = c.id AND p.active = 1";
    // Only attributes whose designation or location is still active in the list's catalog are used.
    static final String ATTRIBUTE_SQL = "SELECT a.member_id, a.kind, d.code AS designation_code, l.name AS location_name, a.verified "
            + "FROM recipient_member_attribute a "
            + "LEFT JOIN recipient_designation d ON d.list_id = a.list_id AND d.code = a.designation_code AND d.active = 1 "
            + "LEFT JOIN recipient_location l ON l.list_id = a.list_id AND l.id = a.location_id AND l.active = 1 "
            + "WHERE a.list_id = ? AND a.member_id IN ";
    static final String SUBGROUP_SQL = "SELECT g.id, g.sub_group_name, g.sub_group_desc FROM list_sub_groups g WHERE g.list_id = ? AND ";
    private static final String[] PROFILE_TEXTS = {"designation_text", "rank_text", "wing_text", "station_text", "zone_text",
            "district_text"};
    // The texts a list's words come from: usernames without their mail domain (as the parser reads them), decoded
    // profile texts and subgroup names. Each is lowercased; patterns are always lowercase.
    private static final String[][] WORD_SOURCES = {
            {"list_members", "LOWER(SUBSTRING_INDEX(list_username, '@', 1))", ""},
            {"recipient_member_profile", "LOWER(CONCAT_WS(' ', designation_text, rank_text, wing_text, station_text, "
                    + "zone_text, district_text))", " AND active = 1"},
            {"list_sub_groups", "LOWER(CONCAT_WS(' ', sub_group_name, sub_group_desc))", ""}};
    private static final int ID_BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final long defaultListId;
    private final Duration ttl;
    private final String locationParents;
    private final Map<Long, Loaded> catalogs = new ConcurrentHashMap<>();

    public MysqlRecipientDirectory(JdbcTemplate jdbc,
                                   @Value("${resolver.mysql.list-id:173}") long defaultListId,
                                   @Value("${resolver.mysql.cache-ttl-seconds:120}") long ttlSeconds,
                                   @Value("${resolver.location-parents:}") String locationParents) {
        this.jdbc = jdbc;
        this.defaultListId = defaultListId;
        this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
        this.locationParents = locationParents;
    }

    @Override
    public View snapshot(Long requestedListId) {
        long listId = requestedListId == null ? defaultListId : requestedListId;
        Loaded cached = catalogs.get(listId);
        if (!fresh(cached)) cached = catalogs.compute(listId, (id, existing) -> fresh(existing) ? existing
                : new Loaded(CatalogEngine.of(loadCatalog(id)), Instant.now()));
        return new ListView(listId, cached.engine());
    }

    @Override
    public Status reload() {
        catalogs.clear();
        View view = snapshot(null);
        Integer members = jdbc.queryForObject("SELECT COUNT(*) FROM list_members WHERE list_id = ?", Integer.class, view.listId());
        Integer subgroups = jdbc.queryForObject("SELECT COUNT(*) FROM list_sub_groups WHERE list_id = ?", Integer.class, view.listId());
        return new Status(view.listId(), members == null ? 0 : members, subgroups == null ? 0 : subgroups,
                catalogs.get(view.listId()).loadedAt());
    }

    private boolean fresh(Loaded loaded) {
        return loaded != null && !ttl.isZero() && loaded.loadedAt().plus(ttl).isAfter(Instant.now());
    }

    private record Loaded(CatalogEngine engine, Instant loadedAt) { }

    /** One request's view of a list: the cached catalog, with every member and word question asked of MySQL. */
    private final class ListView implements View, RecipientMatcher.Vocabulary {
        private final long listId;
        private final CatalogEngine engine;

        ListView(long listId, CatalogEngine engine) {
            this.listId = listId;
            this.engine = engine;
        }

        public long listId() { return listId; }

        public CatalogEngine engine() { return engine; }

        public RecipientMatcher.Vocabulary words() { return this; }

        public List<ParsedRecipient> memberCandidates(RecipientQuery query) {
            CandidateSql usernames = CandidateSql.usernames(listId, query, engine.catalog());
            CandidateSql profiled = CandidateSql.profiled(listId, query, engine.catalog());
            return members(listId, engine, usernames.where, usernames.args, profiled.where, profiled.args);
        }

        public List<ParsedRecipient> subgroupCandidates(RecipientQuery query) {
            CandidateSql sql = CandidateSql.subgroups(listId, query, engine.catalog());
            List<Object> args = new ArrayList<>(List.of(listId));
            args.addAll(sql.args);
            List<ParsedRecipient> out = new ArrayList<>();
            jdbc.query(SUBGROUP_SQL + "(" + sql.where + ")", row -> {
                out.add(engine.parser().subgroup(row.getLong("id"), row.getString("sub_group_name"), row.getString("sub_group_desc")));
            }, args.toArray());
            return out;
        }

        public boolean known(String term) {
            // The same words the parser keeps: whole tokens, unit spellings read as this word, and the letters of
            // a coded token (si1046 -> si); four letters or more may also start a longer word. A plain LIKE finds
            // the rows holding the letters first, so the regular expression only checks those.
            Set<String> spellings = engine.catalog().unitSpellings(term);
            String pattern = "(^|[^a-z0-9])(" + String.join("|", spellings) + ")"
                    + (term.length() >= 4 ? "" : "[0-9]*([^a-z0-9]|$)");
            for (String[] source : WORD_SOURCES) {
                List<Object> args = new ArrayList<>(List.of(listId));
                spellings.forEach(spelling -> args.add("%" + spelling + "%"));
                args.add(pattern);
                String sql = "SELECT EXISTS(SELECT 1 FROM " + source[0] + " WHERE list_id = ?" + source[2] + " AND ("
                        + String.join(" OR ", Collections.nCopies(spellings.size(), source[1] + " LIKE ?"))
                        + ") AND REGEXP_LIKE(" + source[1] + ", ?))";
                if (Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args.toArray()))) return true;
            }
            return false;
        }

        public Set<String> similar(String term) {
            // Within the allowed edits (at most length / 4), one of edits + 1 pieces of the term survives intact,
            // so only texts holding one of the pieces can contain a similar word.
            int pieces = term.length() / 4 + 1;
            List<String> likes = new ArrayList<>();
            for (int i = 0; i < pieces; i++)
                likes.add("%" + term.substring(i * term.length() / pieces, (i + 1) * term.length() / pieces) + "%");
            Set<String> found = new TreeSet<>();
            RecipientCatalog catalog = engine.catalog();
            for (String[] source : WORD_SOURCES) {
                List<Object> args = new ArrayList<>(List.of(listId));
                args.addAll(likes);
                String sql = "SELECT " + source[1] + " AS t FROM " + source[0] + " WHERE list_id = ?" + source[2] + " AND ("
                        + String.join(" OR ", Collections.nCopies(likes.size(), source[1] + " LIKE ?")) + ")";
                jdbc.query(sql, row -> {
                    for (String token : TextNormalizer.tokens(row.getString("t"))) {
                        for (String word : new String[]{token, catalog.unitKeyword(token), TextNormalizer.codePrefix(token)})
                            if (word != null && RecipientMatcher.Vocabulary.isSimilar(term, word)) found.add(word);
                    }
                }, args.toArray());
            }
            return found.stream().limit(5).collect(LinkedHashSet::new, Set::add, Set::addAll);
        }
    }

    /**
     * Members of a list matching the two parts of MEMBER_SQL, read with their decoded profiles where one is
     * still current.
     */
    List<ParsedRecipient> members(long listId, CatalogEngine engine, String usernameWhere, List<Object> usernameArgs,
                                  String profileWhere, List<Object> profileArgs) {
        record Row(long id, String username, String profileUsername, String confidence, List<String> texts) { }
        List<Object> args = new ArrayList<>(List.of(listId));
        args.addAll(usernameArgs);
        args.add(listId);
        args.addAll(profileArgs);
        args.add(listId);
        List<Row> rows = new ArrayList<>();
        jdbc.query(MEMBER_SQL.formatted(usernameWhere, profileWhere), row -> {
            long id = row.getLong("id");
            if (row.wasNull()) throw new IllegalArgumentException("list_members.id cannot be null");
            List<String> texts = new ArrayList<>();
            for (String column : PROFILE_TEXTS) {
                String text = row.getString(column);
                if (text != null && !text.isBlank()) texts.add(text.replace("?", " "));
            }
            rows.add(new Row(id, row.getString("list_username"), row.getString("profile_username"),
                    row.getString("confidence"), List.copyOf(texts)));
        }, args.toArray());

        List<Long> profiled = rows.stream().filter(r -> r.profileUsername() != null).map(Row::id).toList();
        Map<Long, Map<String, Boolean>> designations = new HashMap<>();
        Map<Long, Map<String, Boolean>> locations = new HashMap<>();
        for (int from = 0; from < profiled.size(); from += ID_BATCH) {
            List<Long> batch = profiled.subList(from, Math.min(profiled.size(), from + ID_BATCH));
            List<Object> batchArgs = new ArrayList<>(List.of(listId));
            batchArgs.addAll(batch);
            // Ordered so matched terms and reasons read the same on every request.
            jdbc.query(ATTRIBUTE_SQL + "(" + String.join(", ", Collections.nCopies(batch.size(), "?")) + ") ORDER BY a.id", row -> {
                long memberId = row.getLong("member_id");
                boolean designation = "DESIGNATION".equals(row.getString("kind"));
                String key = row.getString(designation ? "designation_code" : "location_name");
                if (key == null) return;   // deactivated in the catalog
                (designation ? designations : locations).computeIfAbsent(memberId, ignored -> new LinkedHashMap<>())
                        .merge(key, row.getBoolean("verified"), Boolean::logicalOr);
            }, batchArgs.toArray());
        }

        List<ParsedRecipient> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            MemberProfile profile = r.profileUsername() == null ? null : new MemberProfile(r.id(), r.profileUsername(),
                    r.confidence(), r.texts(), designations.getOrDefault(r.id(), Map.of()), locations.getOrDefault(r.id(), Map.of()));
            out.add(engine.parser().member(r.id(), r.username(), profile));
        }
        return out;
    }

    private RecipientCatalog loadCatalog(long listId) {
        List<RecipientCatalog.Phrase> phrases = jdbc.query(PHRASE_SQL, (row, index) -> new RecipientCatalog.Phrase(
                RecipientCatalog.Kind.valueOf(row.getString("kind")), row.getString("catalog_key"),
                row.getString("label"), row.getString("alias"), row.getBoolean("verified"), row.getString("parent_code")), listId);
        Map<String, List<String>> words = new HashMap<>();
        jdbc.query(WORD_SQL, row -> {
            words.computeIfAbsent(row.getString("category"), ignored -> new ArrayList<>()).add(row.getString("word"));
        }, listId);
        RecipientCatalog catalog = RecipientCatalog.fromRows(phrases, words, locationParents);
        if (catalog.isEmpty()) {
            // Without designations, locations and query words every word would become a required keyword.
            log.warn("mysql.catalog.missing list_id={} phrases={} query_word_categories={}", listId, phrases.size(), words.size());
            throw new CatalogNotConfiguredException(listId);
        }
        log.info("mysql.catalog.loaded list_id={} phrases={} query_word_categories={}", listId, phrases.size(), words.size());
        return catalog;
    }
}
