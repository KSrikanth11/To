package com.example.recipient.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.recipient.model.Intent;

@Service
public class AliasService {
    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    private final Supplier<Map<String, List<String>>> dictionary;

    @Autowired
    public AliasService(@Value("${resolver.custom-aliases:}") String custom, RecipientAliasDictionary dictionary) {
        this(custom, dictionary::aliases);
    }

    /** Retained for alias-focused tests using a directly loaded JSON index. */
    public AliasService(String custom, RecipientSearchIndex index) {
        this(custom, () -> index.snapshot().verifiedAliases());
    }

    private AliasService(String custom, Supplier<Map<String, List<String>>> dictionary) {
        this.dictionary = dictionary;
        add("Sub-Inspector", "Sub-Inspector", "Sub Inspector", "SI", "SIs");
        add("Circle Inspector", "Circle Inspector", "Circle Inspectors", "CI", "CIs");
        add("Deputy Superintendent of Police", "Deputy Superintendent of Police", "DSP", "DSPs");
        add("Sub-Divisional Police Officer", "Sub-Divisional Police Officer", "Sub Divisional Police Officer", "SDPO", "SDPOs");
        add("Station House Officer", "Station House Officer", "SHO", "SHOs");
        if (!custom.isBlank()) for (String entry : custom.split(";")) {
            String[] pair = entry.split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) {
                List<String> values = new ArrayList<>(); values.add(pair[0].trim());
                for (String alias : pair[1].split("\\|")) if (!alias.isBlank()) values.add(alias.trim());
                add(pair[0].trim(), values.toArray(String[]::new));
            }
        }
    }

    private void add(String label, String... values) { groups.put(label, List.of(values)); }

    public List<Intent.Concept> concepts(String query) {
        // Recover adjacent letter-number identifiers if an upstream parser joins them.
        String separatedCodes = query == null ? "" : query.replaceAll("(?i)(?<=\\d)(?=[a-z]\\d)", " ");
        System.out.println("Separated codes: " + separatedCodes);
        String normalized = normalize(separatedCodes);
        System.out.println("Normalized query: " + normalized);
        List<Intent.Concept> result = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        Map<String, List<String>> allGroups = new LinkedHashMap<>(dictionary.get());
        allGroups.putAll(groups);
        List<String> designationLabels = new ArrayList<>();
        List<String> designationAlternatives = new ArrayList<>();
        for (var group : allGroups.entrySet()) {
            boolean present = group.getValue().stream().anyMatch(a -> containsPhrase(normalized, normalize(a)));
            if (present) {
                if (isDesignation(group.getKey())) {
                    designationLabels.add(group.getKey());
                    designationAlternatives.addAll(group.getValue());
                } else {
                    result.add(new Intent.Concept(group.getKey(), group.getValue()));
                }
                group.getValue().forEach(a -> consumed.addAll(tokens(a)));
            }
        }
        if (!designationLabels.isEmpty()) {
            String label = designationLabels.size() == 1
                    ? designationLabels.get(0) : String.join(" or ", designationLabels);
            result.add(new Intent.Concept(label, designationAlternatives.stream().distinct().toList()));
        }

        List<String> identifiers = new ArrayList<>();
        for (String token : tokens(separatedCodes)) {
            if (!consumed.contains(token) && !STOP.contains(token)) result.add(new Intent.Concept(token, List.of(token)));
        }
        // Multiple letter-number identifiers are alternatives, not one combined phrase.
        List<Intent.Concept> unitCodes = result.stream()
                .filter(concept -> concept.alternatives().size() == 1
                        && concept.alternatives().get(0).matches("[a-z]+\\d+"))
                .toList();
        if (unitCodes.size() > 1) {
            result.removeAll(unitCodes);
            identifiers.addAll(unitCodes.stream().map(c -> c.alternatives().get(0)).toList());
            String label = identifiers.stream().map(code -> code.toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.joining(" or "));
            result.add(new Intent.Concept(label, identifiers));
        }
        return result;
    }

    private static boolean isDesignation(String label) {
        return Set.of("sub inspector", "circle inspector", "deputy superintendent of police",
                "sub divisional police officer", "station house officer")
                .contains(normalize(label));
    }

    public static List<String> tokens(String value) {
        return Arrays.stream(normalize(value).split(" ")).filter(s -> !s.isBlank()).distinct().toList();
    }
    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("'s\\b", "s")
                .replaceAll("[^\\p{Alnum}]+", " ").trim().replaceAll("\\s+", " ");
    }
    public static boolean containsPhrase(String text, String phrase) {
        return Pattern.compile("(?:^| )" + Pattern.quote(phrase) + "(?:$| )").matcher(text).find();
    }
    private static final Set<String> STOP = Set.of("send", "find", "select", "to", "the", "all", "every", "each", "for", "of", "in", "at", "from", "and", "please", "message", "notify", "text", "with", "members", "groups", "subgroups", "related", "officer", "officers", "police", "recipient", "recipients");
}
