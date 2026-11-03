package com.example.recipient.searchRecipient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import com.example.recipient.searchRecipient.model.RecipientQuery;
import com.example.recipient.searchRecipient.service.RecipientCatalog;

/**
 * A WHERE clause that selects every row RecipientMatcher.match could accept, so a search reads only those rows.
 * Each filter becomes LIKE patterns on the row's own text: the normalizer only lowercases and splits, so every
 * token it reads still appears in the text, in order, and "%" stands for whatever split them. The few places it
 * joins characters (L&O and "law and order" -> lo, "'s" -> s) get their own patterns. The clause can return extra
 * rows but never misses one; the matcher then applies the exact rules (whole words, exclusions, verification).
 */
final class CandidateSql {
    /** Members by username alone (alias m): the scan of list_members, so it reads no other table. */
    static CandidateSql usernames(long listId, RecipientQuery query, RecipientCatalog catalog) {
        return new CandidateSql(listId, query, catalog, "LOWER(m.list_username)", false);
    }

    /** Members with a decoded profile (aliases m and p): the username, or the profile's attributes and texts. */
    static CandidateSql profiled(long listId, RecipientQuery query, RecipientCatalog catalog) {
        return new CandidateSql(listId, query, catalog, "LOWER(m.list_username)", true);
    }

    /** Subgroups: the name and description. */
    static CandidateSql subgroups(long listId, RecipientQuery query, RecipientCatalog catalog) {
        return new CandidateSql(listId, query, catalog, "LOWER(CONCAT_WS(' ', g.sub_group_name, g.sub_group_desc))", false);
    }

    /** The decoded profile's texts (the p alias of MysqlRecipientDirectory.MEMBER_SQL's profile part). */
    static final String PROFILE_TEXT = "LOWER(CONCAT_WS(' ', p.designation_text, p.rank_text, p.wing_text, "
            + "p.station_text, p.zone_text, p.district_text))";

    final String where;
    final List<Object> args = new ArrayList<>();

    private final long listId;
    private final String text;
    private final boolean profiles;

    private CandidateSql(long listId, RecipientQuery query, RecipientCatalog catalog, String text, boolean profiles) {
        this.listId = listId;
        this.text = text;
        this.profiles = profiles;
        StringBuilder sql = new StringBuilder();
        if (query.hasFilters()) {
            List<String> required = new ArrayList<>();
            if (!query.designations().isEmpty()) {
                Set<String> codes = catalog.withNarrower(query.designations());
                required.add(anyOf(catalog.phrasesFor(RecipientCatalog.Kind.DESIGNATION, codes), clause -> attribute(clause,
                        "a.kind = 'DESIGNATION' AND a.designation_code", codes, "")));
            }
            if (!query.locations().isEmpty()) {
                Set<String> places = query.expandedLocations();
                required.add(anyOf(catalog.phrasesFor(RecipientCatalog.Kind.LOCATION, places), clause -> attribute(clause,
                        "a.kind = 'LOCATION' AND l.name", places,
                        " JOIN recipient_location l ON l.list_id = a.list_id AND l.id = a.location_id")));
            }
            for (RecipientQuery.Keyword keyword : query.keywords()) {
                Set<String> forms = new TreeSet<>();
                forms.addAll(catalog.unitSpellings(keyword.term()));
                keyword.fuzzyForms().forEach(form -> forms.addAll(catalog.unitSpellings(form)));
                required.add(anyOf(forms, clause -> profileText(clause, forms)));
            }
            sql.append(String.join(" AND ", required));
        } else {
            sql.append("FALSE");
        }
        if (!query.exactKey().isEmpty()) {
            List<String> exact = new ArrayList<>();
            for (String pattern : likes(query.exactKey())) {
                exact.add(text + " LIKE ?");
                args.add(pattern);
            }
            sql.insert(0, "(").append(") OR ").append(String.join(" OR ", exact));
        }
        this.where = sql.toString();
    }

    /** One filter: the text holds one of the phrases, or (members) the decoded profile says so. */
    private String anyOf(Collection<String> phrases, Consumer<StringBuilder> profileClause) {
        List<String> options = new ArrayList<>();
        Set<String> patterns = new LinkedHashSet<>();
        phrases.forEach(phrase -> patterns.addAll(likes(phrase)));
        for (String pattern : withoutCovered(patterns)) {
            options.add(text + " LIKE ?");
            args.add(pattern);
        }
        if (profiles) {
            StringBuilder clause = new StringBuilder();
            profileClause.accept(clause);
            if (!clause.isEmpty()) options.add(clause.toString());
        }
        return options.isEmpty() ? "FALSE" : "(" + String.join(" OR ", options) + ")";
    }

    private void attribute(StringBuilder sql, String column, Set<String> values, String join) {
        if (values.isEmpty()) return;
        sql.append("m.id IN (SELECT a.member_id FROM recipient_member_attribute a").append(join)
                .append(" WHERE a.list_id = ? AND ").append(column).append(" IN (")
                .append(String.join(", ", values.stream().map(v -> "?").toList())).append("))");
        args.add(listId);
        args.addAll(values);
    }

    private void profileText(StringBuilder sql, Set<String> forms) {
        Set<String> patterns = new LinkedHashSet<>();
        forms.forEach(form -> patterns.addAll(likes(form)));
        List<String> options = new ArrayList<>();
        for (String pattern : withoutCovered(patterns)) {
            options.add(PROFILE_TEXT + " LIKE ?");
            args.add(pattern);
        }
        sql.append(String.join(" OR ", options));
    }

    /** Drops each pattern another one already covers: whatever "%sub%inspectors%" matches, "%sub%inspector%" does too. */
    static List<String> withoutCovered(Collection<String> patterns) {
        List<String> distinct = List.copyOf(new LinkedHashSet<>(patterns));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            String pattern = distinct.get(i);
            boolean covered = false;
            for (int j = 0; j < distinct.size() && !covered; j++) {
                String other = distinct.get(j);
                // Of two equal-coverage patterns keep the first; distinct patterns never cover each other both ways.
                covered = j != i && covers(other, pattern) && (!covers(pattern, other) || j < i);
            }
            if (!covered) out.add(pattern);
        }
        return out;
    }

    /** True when every text matching inner also matches outer: outer's pieces sit, in order, inside inner's pieces. */
    static boolean covers(String outer, String inner) {
        String[] want = pieces(outer), have = pieces(inner);
        int piece = 0, from = 0;
        for (String next : want) {
            while (piece < have.length && have[piece].indexOf(next, from) < 0) { piece++; from = 0; }
            if (piece == have.length) return false;
            from = have[piece].indexOf(next, from) + next.length();
        }
        return true;
    }

    private static String[] pieces(String pattern) {
        return java.util.Arrays.stream(pattern.split("%")).filter(piece -> !piece.isEmpty()).toArray(String[]::new);
    }

    /**
     * LIKE patterns for a normalized phrase: "sub inspector" -> "%sub%inspector%", plus the raw spellings the
     * normalizer joins: the word "lo" may be written l&o or law and order, and a word's last "s" may follow an apostrophe.
     */
    static Set<String> likes(String phrase) {
        Set<String> spellings = new LinkedHashSet<>(List.of(phrase));
        String padded = " " + phrase + " ";
        if (padded.contains(" lo ")) {
            spellings.add(padded.replace(" lo ", " l o ").trim());
            spellings.add(padded.replace(" lo ", " law order ").trim());
        }
        for (String spelling : List.copyOf(spellings)) {
            String apostrophe = spelling.replaceAll("(?<=[a-z0-9])s\\b", " s");
            if (!apostrophe.equals(spelling)) spellings.add(apostrophe);
        }
        Set<String> out = new LinkedHashSet<>();
        spellings.forEach(spelling -> out.add("%" + String.join("%", spelling.split(" ")) + "%"));
        return out;
    }
}
