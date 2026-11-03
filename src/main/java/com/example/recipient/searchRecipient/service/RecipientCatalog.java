package com.example.recipient.searchRecipient.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The designations, locations, unit spellings and query words one list understands, used identically
 * for queries and directory rows. In MySQL mode each list's catalog is built from its rows in the
 * recipient_* catalog tables ({@link #fromRows}); the offline index mode and tests use the built-in
 * defaults plus the JSON alias dictionary ({@link #fromDefaults}), which match the seeded tables.
 */
public class RecipientCatalog {
    private static final Logger log = LoggerFactory.getLogger(RecipientCatalog.class);

    /** Query word categories; the same values as recipient_query_word.category. */
    public static final String COMMAND = "COMMAND", FILLER = "FILLER", ALL_SCOPE = "ALL_SCOPE", GENERIC = "GENERIC",
            GROUP_HINT = "GROUP_HINT", MEMBER_HINT = "MEMBER_HINT", EXCLUSION = "EXCLUSION";
    /** Categories whose words carry no search meaning and are dropped from queries. */
    private static final Set<String> IGNORED_CATEGORIES = Set.of(COMMAND, FILLER, ALL_SCOPE, GENERIC, GROUP_HINT, MEMBER_HINT);

    public enum Kind { DESIGNATION, LOCATION, UNIT }
    public record Entry(Kind kind, String key, boolean verified) { }
    /** One row of the recipient_catalog_phrase view; parent is the wider designation (WSI -> SI), if any. */
    public record Phrase(Kind kind, String key, String label, String alias, boolean verified, String parent) {
        public Phrase(Kind kind, String key, String label, String alias, boolean verified) {
            this(kind, key, label, alias, verified, null);
        }
    }
    /**
     * Result of reading a token sequence: recognized designations and locations, everything else, and the
     * phrases that name several different places ("mlg" -> Mulugu District, Miryalaguda, ...).
     */
    public record Annotation(Set<String> designations, Map<String, Boolean> locations, List<String> rest,
                             boolean allPrefix, Map<String, List<String>> ambiguous) { }

    /** A phrase maps to one entry, or to several locations when one code names different places. */
    private final Map<String, List<Entry>> phrases = new HashMap<>();
    private final Map<String, String> designationLabels = new LinkedHashMap<>();
    private final Map<String, String> designationParents = new HashMap<>();
    private final Set<String> locationKeys = new LinkedHashSet<>();
    private final Map<String, String> unitForms = new HashMap<>();
    private final Map<String, Set<String>> queryWords = new HashMap<>();
    private final Set<String> ignoredWords = new HashSet<>();
    private final Map<String, Set<String>> children = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private int longestPhrase = 1;

    private RecipientCatalog() { }

    /** Builds one list's catalog from its catalog table rows. */
    public static RecipientCatalog fromRows(Collection<Phrase> rows, Map<String, ? extends Collection<String>> words,
                                            String locationParents) {
        RecipientCatalog catalog = new RecipientCatalog();
        // Designations first, so a designation keeps its alias if a location row reuses it.
        rows.stream().filter(row -> row.kind() == Kind.DESIGNATION).forEach(row -> {
            catalog.designation(row.key(), row.label(), row.alias());
            if (row.parent() != null && !row.parent().isBlank()) catalog.designationParents.put(row.key(), row.parent());
        });
        rows.stream().filter(row -> row.kind() == Kind.LOCATION).forEach(row -> {
            catalog.locationKeys.add(row.key());
            catalog.location(row.key(), row.alias(), row.verified());
        });
        rows.stream().filter(row -> row.kind() == Kind.UNIT).forEach(row -> catalog.unit(row.key(), row.alias()));
        words.forEach((category, values) -> values.forEach(word -> catalog.queryWord(category, word)));
        return catalog.finish(locationParents);
    }

    /** Built-in defaults plus the JSON alias dictionary and configured extras (offline index mode). */
    public static RecipientCatalog fromDefaults(Map<String, List<String>> verified, Map<String, List<String>> unverified,
                                                String customLocations, String customDesignations, String customUnits,
                                                String locationParents) {
        RecipientCatalog catalog = new RecipientCatalog();
        catalog.designation("SI", "Sub-Inspector", "si", "sis", "wsi", "wsis", "sub inspector", "sub inspectors",
                "subinspector", "subinspectors");
        catalog.designation("CI", "Circle Inspector", "ci", "cis", "circle inspector", "circle inspectors");
        catalog.designation("INSPR", "Inspector", "inspr", "insprs", "insp", "inspector", "inspectors");
        catalog.designation("SHO", "Station House Officer", "sho", "shos", "station house officer", "station house officers");
        catalog.designation("DSP", "Deputy Superintendent of Police", "dsp", "dsps", "deputy superintendent of police",
                "deputy superintendents of police");
        catalog.designation("SDPO", "Sub-Divisional Police Officer", "sdpo", "sdpos", "sub divisional police officer",
                "sub divisional police officers");
        catalog.designation("ACP", "Assistant Commissioner of Police", "acp", "acps", "assistant commissioner of police");
        catalog.designation("ADCP", "Additional DCP", "adcp", "adcps", "addl dcp", "additional dcp",
                "additional deputy commissioner of police");
        catalog.designation("DCP", "Deputy Commissioner of Police", "dcp", "dcps", "deputy commissioner of police");
        catalog.designation("ADDLSP", "Additional SP", "addlsp", "addl sp", "additional sp",
                "additional superintendent of police");
        catalog.designation("SP", "Superintendent of Police", "sp", "sps", "superintendent of police");
        catalog.designation("CP", "Commissioner of Police", "cp", "commissioner of police");
        catalog.designation("RI", "Reserve Inspector", "ri", "ris", "reserve inspector", "reserve inspectors");
        catalog.designation("DI", "Detective Inspector", "di", "dis", "detective inspector", "detective inspectors");
        // Unit and wing spellings seen in usernames, e.g. acp-trf-nzb and sho-blnr-tfc-kz-cyb.
        catalog.unit("traffic", "tfc", "trf", "trfc", "trafic", "trafffic");
        catalog.unit("lo", "lando", "landorder");
        parsePairs(customUnits).forEach((canonical, values) -> catalog.unit(canonical, values.toArray(String[]::new)));
        for (var entry : parsePairs(customDesignations).entrySet()) {
            String code = entry.getKey().toUpperCase(java.util.Locale.ROOT);
            List<String> values = new ArrayList<>(entry.getValue());
            values.add(entry.getKey());
            catalog.designation(code, catalog.designationLabels.getOrDefault(code, code), values.toArray(String[]::new));
        }

        // Any code listed as unverified stays unverified, even if it also appears in the verified map.
        Set<String> unverifiedPhrases = new HashSet<>();
        unverified.values().forEach(values -> values.stream().map(TextNormalizer::normalize)
                .filter(phrase -> !phrase.isEmpty() && phrase.split(" ").length <= 3)
                .forEach(unverifiedPhrases::add));
        verified.forEach((canonical, values) -> {
            catalog.locationKeys.add(canonical);
            values.forEach(alias -> catalog.location(canonical, alias, !unverifiedPhrases.contains(TextNormalizer.normalize(alias))));
        });
        unverified.forEach((canonical, values) -> {
            if (!catalog.locationKeys.contains(canonical)) return;
            values.forEach(alias -> {
                String phrase = TextNormalizer.normalize(alias);
                if (unverifiedPhrases.contains(phrase)) catalog.location(canonical, alias, false);
            });
        });
        // Configured aliases are an administrator's confirmation, so they also upgrade unverified codes.
        parsePairs(customLocations).forEach((canonical, values) -> {
            catalog.locationKeys.add(canonical);
            catalog.register(TextNormalizer.normalize(canonical), new Entry(Kind.LOCATION, canonical, true), true);
            values.forEach(alias -> catalog.register(TextNormalizer.normalize(alias), new Entry(Kind.LOCATION, canonical, true), true));
        });
        DEFAULT_QUERY_WORDS.forEach((category, values) -> values.forEach(word -> catalog.queryWord(category, word)));
        return catalog.finish(locationParents);
    }

    /** The query words seeded into recipient_query_word; used as-is by the offline index mode. */
    public static final Map<String, List<String>> DEFAULT_QUERY_WORDS = Map.of(
            COMMAND, List.of("send", "find", "select", "get", "give", "list", "fetch", "show", "search", "add", "choose",
                    "notify", "message", "messages", "text", "mail", "inform", "tell", "please", "pls", "kindly"),
            FILLER, List.of("to", "the", "for", "of", "in", "at", "from", "and", "with", "a", "an", "on", "under", "within",
                    "into", "who", "are", "is", "working", "posted", "me", "my", "our", "us", "them", "those", "these",
                    "or", "also", "too", "only", "both", "it", "this", "that", "now", "him", "her", "their", "there", "here"),
            ALL_SCOPE, List.of("all", "every", "each", "entire", "whole"),
            GENERIC, List.of("everyone", "everybody", "anyone", "anybody", "user", "users", "people", "person", "persons",
                    "recipient", "recipients", "individual", "individuals", "officer", "officers", "official", "officials",
                    "staff", "personnel", "team", "teams", "concerned", "respective", "related", "available", "active",
                    "sir", "sirs", "madam", "garu", "gaaru", "suraksha", "telangana", "police", "department", "dept",
                    "district", "districts", "dist", "station", "stations", "ps", "pss", "commissionerate",
                    "commissionarate", "zone", "zones", "division", "divisions", "subdivision", "subdivisions", "sub",
                    "unit", "units", "area", "areas", "region", "rank", "ranks", "wing", "wings"),
            GROUP_HINT, List.of("group", "groups", "subgroup", "subgroups"),
            MEMBER_HINT, List.of("member", "members", "individual", "individuals"),
            EXCLUSION, List.of("except", "excluding", "exclude", "without", "but", "not"));

    private RecipientCatalog finish(String locationParents) {
        parsePairs(locationParents).forEach((child, values) -> {
            if (values.isEmpty()) return;
            String parent = values.get(0);
            if (!locationKeys.contains(child) || !locationKeys.contains(parent)) {
                log.warn("catalog.location_parent.ignored child={} parent={} reason=unknown_location", child, parent);
                return;
            }
            children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(child);
            parents.put(child, parent);
        });
        log.info("catalog.loaded designations={} locations={} phrases={} units={} query_words={} parent_links={}",
                designationLabels.size(), locationKeys.size(), phrases.size(), unitForms.size(),
                queryWords.values().stream().mapToInt(Set::size).sum(), parents.size());
        return this;
    }

    private void designation(String code, String label, String... aliases) {
        designationLabels.put(code, label);
        for (String alias : aliases) register(TextNormalizer.normalize(alias), new Entry(Kind.DESIGNATION, code, true), true);
    }

    private void unit(String canonical, String... forms) {
        String key = TextNormalizer.normalize(canonical).replace(" ", "");
        unitForms.put(key, key);
        for (String form : forms) unitForms.put(TextNormalizer.normalize(form).replace(" ", ""), key);
    }

    private void location(String canonical, String alias, boolean verified) {
        register(TextNormalizer.normalize(alias), new Entry(Kind.LOCATION, canonical, verified), false);
    }

    private void queryWord(String category, String word) {
        String normalized = TextNormalizer.normalize(word);
        if (normalized.isEmpty()) return;
        queryWords.computeIfAbsent(category, ignored -> new HashSet<>()).add(normalized);
        if (IGNORED_CATEGORIES.contains(category)) ignoredWords.add(normalized);
    }

    private void register(String phrase, Entry entry, boolean replace) {
        if (phrase.isEmpty()) return;
        List<Entry> existing = phrases.get(phrase);
        longestPhrase = Math.max(longestPhrase, phrase.split(" ").length);
        if (existing == null) {
            phrases.put(phrase, new ArrayList<>(List.of(entry)));
            return;
        }
        Entry first = existing.get(0);
        if (first.kind() != entry.kind()) {
            log.warn("catalog.alias.conflict phrase={} kept={} ignored={}", phrase, first.key(), entry.key());
            return;
        }
        if (replace) {
            phrases.put(phrase, new ArrayList<>(List.of(entry)));
            return;
        }
        if (entry.kind() == Kind.DESIGNATION) {
            if (!first.key().equals(entry.key()))
                log.warn("catalog.alias.conflict phrase={} kept={} ignored={}", phrase, first.key(), entry.key());
            return;
        }
        Optional<Entry> same = existing.stream().filter(e -> e.key().equals(entry.key())).findFirst();
        if (same.isPresent()) {
            // The same place listed twice: an unverified listing wins, as before.
            if (same.get().verified() && !entry.verified()) existing.set(existing.indexOf(same.get()), entry);
            return;
        }
        // One alias, two different places: keep both meanings, neither of which can confirm a match alone.
        log.info("catalog.alias.ambiguous phrase={} locations={},{}", phrase, first.key(), entry.key());
        existing.replaceAll(e -> new Entry(e.kind(), e.key(), false));
        existing.add(new Entry(entry.kind(), entry.key(), false));
    }

    /** Greedy longest-phrase reading of a token sequence. */
    public Annotation annotate(List<String> tokens) {
        Set<String> designations = new LinkedHashSet<>();
        Map<String, Boolean> locations = new LinkedHashMap<>();
        Map<String, List<String>> ambiguous = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();
        boolean allPrefix = false;
        int i = 0;
        while (i < tokens.size()) {
            int consumed = 0;
            for (int length = Math.min(longestPhrase, tokens.size() - i); length >= 1 && consumed == 0; length--) {
                String phrase = String.join(" ", tokens.subList(i, i + length));
                List<Entry> entries = phrases.get(phrase);
                if (entries != null) { add(phrase, entries, designations, locations, ambiguous); consumed = length; }
            }
            if (consumed == 0) {
                String token = tokens.get(i);
                List<Entry> entries = compound(token);
                if (entries != null) {
                    add(token, entries, designations, locations, ambiguous);
                    allPrefix |= token.startsWith("all") && TextNormalizer.codePrefix(token) == null;
                } else {
                    rest.add(token);
                }
                consumed = 1;
            }
            i += consumed;
        }
        return new Annotation(designations, locations, rest, allPrefix, ambiguous);
    }

    /** Reads coded tokens such as si1046 or ci1, and compounds such as allcis or allsdpos. */
    private List<Entry> compound(String token) {
        String prefix = TextNormalizer.codePrefix(token);
        if (prefix != null && phrases.containsKey(prefix)) return phrases.get(prefix);
        if (token.startsWith("all") && token.length() > 4) return phrases.get(token.substring(3));
        return null;
    }

    private void add(String phrase, List<Entry> entries, Set<String> designations, Map<String, Boolean> locations,
                     Map<String, List<String>> ambiguous) {
        for (Entry entry : entries) {
            if (entry.kind() == Kind.DESIGNATION) designations.add(entry.key());
            else locations.merge(entry.key(), entry.verified(), Boolean::logicalOr);
        }
        if (entries.size() > 1) ambiguous.put(phrase, entries.stream().map(Entry::key).toList());
    }

    /** The designation and every wider one it belongs to (WSI -> WSI, SI). */
    public List<String> withParents(String code) {
        List<String> codes = new ArrayList<>();
        for (String next = code; next != null && !codes.contains(next); next = designationParents.get(next)) codes.add(next);
        return codes;
    }

    /** Every phrase this catalog reads as one of these designation codes or location names. */
    public Set<String> phrasesFor(Kind kind, Set<String> keys) {
        Set<String> out = new TreeSet<>();
        phrases.forEach((phrase, entries) -> {
            if (entries.stream().anyMatch(entry -> entry.kind() == kind && keys.contains(entry.key()))) out.add(phrase);
        });
        return out;
    }

    /** These codes and every narrower one beneath them, which a record also counts as (WSI counts as SI). */
    public Set<String> withNarrower(Set<String> codes) {
        Set<String> known = new TreeSet<>(designationLabels.keySet());
        phrases.values().forEach(entries -> entries.stream().filter(entry -> entry.kind() == Kind.DESIGNATION)
                .forEach(entry -> known.add(entry.key())));
        Set<String> out = new TreeSet<>(codes);
        known.stream().filter(code -> withParents(code).stream().anyMatch(codes::contains)).forEach(out::add);
        return out;
    }

    /** The spellings unitKeyword reads as this word, the word itself included. */
    public Set<String> unitSpellings(String keyword) {
        Set<String> out = new TreeSet<>(Set.of(keyword));
        unitForms.forEach((form, canonical) -> { if (canonical.equals(keyword)) out.add(form); });
        return out;
    }

    /** A single catalog entry close to a misheard or misspelled word, if exactly one exists. */
    public Optional<Entry> nearest(String token) {
        if (token.length() < 5) return Optional.empty();
        Map<String, Entry> found = new LinkedHashMap<>();
        phrases.forEach((phrase, entries) -> {
            if (!phrase.contains(" ") && phrase.length() >= 5 && TextNormalizer.similarity(token, phrase) >= 0.80)
                entries.forEach(entry -> found.putIfAbsent(entry.kind() + ":" + entry.key(), entry));
        });
        return found.size() == 1 ? Optional.of(found.values().iterator().next()) : Optional.empty();
    }

    /** The location plus every location beneath it in the configured hierarchy. */
    public Set<String> expand(Set<String> locations) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(locations);
        while (!pending.isEmpty()) {
            String next = pending.pop();
            if (result.add(next)) pending.addAll(children.getOrDefault(next, Set.of()));
        }
        return result;
    }

    /** The canonical spelling of a unit or wing word (tfc -> traffic); other words are returned unchanged. */
    public String unitKeyword(String token) { return unitForms.getOrDefault(token, token); }

    public boolean isQueryWord(String category, String token) {
        return queryWords.getOrDefault(category, Set.of()).contains(token);
    }

    /** Command, filler, scope, generic and type-hint words: none of them is a search filter. */
    public boolean isIgnoredWord(String token) { return ignoredWords.contains(token); }

    public Optional<String> parentOf(String location) { return Optional.ofNullable(parents.get(location)); }

    public String label(String designationOrLocation) {
        return designationLabels.getOrDefault(designationOrLocation, designationOrLocation);
    }

    /** The catalog as phrase rows, in the shape of recipient_catalog_phrase (used to check parity). */
    public List<Phrase> rows() {
        List<Phrase> rows = new ArrayList<>();
        phrases.forEach((phrase, entries) -> entries.forEach(entry -> rows.add(new Phrase(entry.kind(), entry.key(),
                label(entry.key()), phrase, entry.verified(), designationParents.get(entry.key())))));
        unitForms.forEach((form, canonical) -> rows.add(new Phrase(Kind.UNIT, canonical, canonical, form, true)));
        return rows;
    }

    public Map<String, Set<String>> queryWords() {
        Map<String, Set<String>> copy = new HashMap<>();
        queryWords.forEach((category, words) -> copy.put(category, Set.copyOf(words)));
        return copy;
    }

    public boolean isEmpty() { return phrases.isEmpty() || ignoredWords.isEmpty(); }

    private static Map<String, List<String>> parsePairs(String value) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        for (String entry : value.split(";")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) continue;
            List<String> values = new ArrayList<>();
            for (String alias : pair[1].split("\\|")) if (!alias.isBlank()) values.add(alias.trim());
            result.put(pair[0].trim(), values);
        }
        return result;
    }
}
