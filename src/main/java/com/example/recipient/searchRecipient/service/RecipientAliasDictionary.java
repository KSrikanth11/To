package com.example.recipient.searchRecipient.service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Loads the JSON alias dictionaries for the offline index mode; MySQL mode reads the catalog tables instead. */
@Service
@ConditionalOnProperty(name = "resolver.source", havingValue = "index", matchIfMissing = true)
public class RecipientAliasDictionary {
    private static final String VERIFIED_FIELD = "verified_or_strongly_supported_aliases";
    private static final String UNVERIFIED_FIELD = "unverified_candidates";
    private final ResourceLoader resources;
    private final ObjectMapper mapper;
    private final String configuredPath;
    private final AtomicReference<Map<String, List<String>>> aliases = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, List<String>>> unverified = new AtomicReference<>(Map.of());

    public RecipientAliasDictionary(ResourceLoader resources, ObjectMapper mapper,
                                    @Value("${resolver.index-path:classpath:suraksha_recipient_search_index.json}") String configuredPath) {
        this.resources = resources;
        this.mapper = mapper;
        this.configuredPath = configuredPath;
    }

    @PostConstruct
    public void load() {
        Resource resource = resources.getResource(resolvePath(configuredPath));
        try (InputStream input = resource.getInputStream(); var parser = mapper.getFactory().createParser(input)) {
            boolean foundVerified = false;
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) continue;
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (VERIFIED_FIELD.equals(field) || UNVERIFIED_FIELD.equals(field)) {
                    Map<String, List<String>> parsed = mapper.readValue(parser,
                            new TypeReference<LinkedHashMap<String, List<String>>>() { });
                    if (VERIFIED_FIELD.equals(field)) {
                        aliases.set(immutableAliases(parsed));
                        foundVerified = true;
                    } else {
                        // Keys here are descriptions of what a code might mean, not aliases themselves.
                        Map<String, List<String>> codes = new LinkedHashMap<>();
                        parsed.forEach((canonical, values) -> codes.put(canonical, values == null ? List.of()
                                : values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList()));
                        unverified.set(Collections.unmodifiableMap(codes));
                    }
                } else if (valueToken == JsonToken.START_ARRAY || valueToken == JsonToken.START_OBJECT) {
                    parser.skipChildren();
                }
            }
            if (!foundVerified) throw new IllegalArgumentException("Index does not contain verified alias dictionary");
        } catch (Exception ex) {
            throw new IllegalStateException("Recipient alias dictionary could not be loaded", ex);
        }
    }

    public Map<String, List<String>> aliases() { return aliases.get(); }

    /** Codes the dictionary author could not confirm; matches through them are only suggestions. */
    public Map<String, List<String>> unverifiedAliases() { return unverified.get(); }

    private static Map<String, List<String>> immutableAliases(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((canonical, values) -> {
            List<String> all = new ArrayList<>();
            all.add(canonical);
            if (values != null) all.addAll(values);
            copy.put(canonical, List.copyOf(all.stream().filter(v -> v != null && !v.isBlank()).distinct().toList()));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String resolvePath(String path) {
        return path.startsWith("classpath:") || path.startsWith("file:") ? path : "file:" + path;
    }
}
