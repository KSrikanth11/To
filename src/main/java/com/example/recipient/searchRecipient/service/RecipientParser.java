package com.example.recipient.searchRecipient.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.recipient.searchRecipient.model.MemberProfile;
import com.example.recipient.searchRecipient.model.ParsedRecipient;

/**
 * Reads members and subgroups into fields. A member with a decoded profile takes its designations and
 * locations from the profile; otherwise the username ("si-ahtu-nlg@suraksha") is read with the catalog.
 */
public class RecipientParser {
    private final RecipientCatalog catalog;

    public RecipientParser(RecipientCatalog catalog) { this.catalog = catalog; }

    public ParsedRecipient member(long id, String username) {
        String name = clean(username);
        String local = TextNormalizer.stripDomain(name);
        Fields fields = new Fields();
        fields.read(List.of(TextNormalizer.tokens(local)), true);
        return fields.build("member", id, name, "", TextNormalizer.normalize(local));
    }

    /**
     * A member read from its decoded profile. The profile is used only while it was decoded from the same
     * username; after a rename the username is read instead, so a stale profile never describes someone else.
     */
    public ParsedRecipient member(long id, String username, MemberProfile profile) {
        if (profile == null || !sameUsername(profile.username(), username)) return member(id, username);
        String name = clean(username);
        String local = TextNormalizer.stripDomain(name);
        Fields fields = new Fields();
        fields.read(List.of(TextNormalizer.tokens(local)), false);
        profile.texts().forEach(text -> fields.read(List.of(TextNormalizer.tokens(text)), false));
        if (!MemberProfile.SYSTEM.equals(profile.confidence())) {
            profile.designations().forEach((code, verified) ->
                    catalog.withParents(code).forEach(c -> fields.designations.merge(c, verified, Boolean::logicalOr)));
            profile.locations().forEach((location, verified) -> fields.locations.merge(location, verified, Boolean::logicalOr));
        }
        return fields.build("member", id, name, "", TextNormalizer.normalize(local));
    }

    public ParsedRecipient subgroup(long id, String name, String description) {
        String cleanName = clean(name);
        String cleanDescription = clean(description);
        Fields fields = new Fields();
        // Name and description are read separately so a phrase never spans the two fields.
        fields.read(List.of(TextNormalizer.tokens(cleanName), TextNormalizer.tokens(cleanDescription)), true);
        return fields.build("subgroup", id, cleanName, cleanDescription, TextNormalizer.normalize(cleanName));
    }

    public static boolean sameUsername(String left, String right) {
        return clean(left).toLowerCase(Locale.ROOT).equals(clean(right).toLowerCase(Locale.ROOT));
    }

    private final class Fields {
        // Keep reading order: Set.copyOf/Map.copyOf iterate in a per-JVM random order, which would make
        // matched_terms and match_reason differ between servers and restarts.
        final Map<String, Boolean> designations = new LinkedHashMap<>();
        final Map<String, Boolean> locations = new LinkedHashMap<>();
        final Set<String> keywords = new LinkedHashSet<>();

        /** Adds every token as a keyword and, when annotate is set, the designations and locations it names. */
        void read(List<List<String>> fieldTokens, boolean annotate) {
            for (List<String> tokens : fieldTokens) {
                if (annotate) {
                    RecipientCatalog.Annotation annotation = catalog.annotate(tokens);
                    // A record holding a rank also holds the wider ones (a WSI is an SI); queries are not widened.
                    annotation.designations().forEach(code ->
                            catalog.withParents(code).forEach(c -> designations.merge(c, true, Boolean::logicalOr)));
                    annotation.locations().forEach((location, verified) -> locations.merge(location, verified, Boolean::logicalOr));
                }
                for (String token : tokens) {
                    keywords.add(token);
                    keywords.add(catalog.unitKeyword(token));
                    String prefix = TextNormalizer.codePrefix(token);
                    if (prefix != null) keywords.add(prefix);
                }
            }
        }

        ParsedRecipient build(String type, long id, String name, String description, String exactKey) {
            return new ParsedRecipient(type, id, name, description, Collections.unmodifiableMap(designations),
                    Collections.unmodifiableMap(locations), Set.copyOf(keywords), exactKey);
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
