package com.example.recipient.searchRecipient.service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
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

/** Loads only the verified alias dictionary; it skips index records in MySQL mode. */
@Service
public class RecipientAliasDictionary {
    private static final String ALIAS_FIELD = "verified_or_strongly_supported_aliases";
    private final ResourceLoader resources;
    private final ObjectMapper mapper;
    private final String configuredPath;
    private final AtomicReference<Map<String, List<String>>> aliases = new AtomicReference<>(Map.of());

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
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) continue;
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (ALIAS_FIELD.equals(field)) {
                    Map<String, List<String>> parsed = mapper.readValue(parser,
                            new TypeReference<LinkedHashMap<String, List<String>>>() { });
                    aliases.set(immutableAliases(parsed));
                    return;
                }
                if ("records".equals(field) && valueToken != null) parser.skipChildren();
            }
            throw new IllegalArgumentException("Index does not contain verified alias dictionary");
        } catch (Exception ex) {
            throw new IllegalStateException("Recipient alias dictionary could not be loaded", ex);
        }
    }

    public Map<String, List<String>> aliases() { return aliases.get(); }

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
