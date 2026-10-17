package com.example.recipient.service;

import com.example.recipient.model.RecipientIndexRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory recipient index. Created only for RESOLVER_SOURCE=index. */
@Service
@ConditionalOnProperty(name = "resolver.source", havingValue = "index", matchIfMissing = true)
public class RecipientSearchIndex {
    private static final Logger log = LoggerFactory.getLogger(RecipientSearchIndex.class);
    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper;
    private final String configuredPath;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public RecipientSearchIndex(ResourceLoader resourceLoader, ObjectMapper mapper,
                                @Value("${resolver.index-path:classpath:suraksha_recipient_search_index.json}") String configuredPath) {
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
        this.configuredPath = configuredPath;
    }

    @PostConstruct
    public void initialize() { reload(); }

    /** Loads and validates fully before atomically publishing the new immutable index. */
    public IndexStatus reload() {
        try {
            Resource resource = resourceLoader.getResource(resolvePath(configuredPath));
            try (InputStream input = resource.getInputStream()) {
                Snapshot snapshot = parse(mapper.readTree(input));
                current.set(snapshot);
                log.info("Recipient JSON index loaded: members={} subgroups={}",
                        snapshot.members().size(), snapshot.subgroups().size());
                return new IndexStatus(snapshot.members().size(), snapshot.subgroups().size(), snapshot.loadedAt());
            }
        } catch (Exception ex) {
            log.error("Recipient JSON index load failed: error_type={}", ex.getClass().getSimpleName());
            throw new IllegalStateException("Recipient JSON index could not be loaded or validated", ex);
        }
    }

    public Snapshot snapshot() {
        Snapshot snapshot = current.get();
        if (snapshot == null) throw new IllegalStateException("Recipient JSON index is not loaded");
        return snapshot;
    }

    private Snapshot parse(JsonNode root) {
        if (!root.isObject() || root.path("version").asInt(-1) != 1 || !root.path("records").isArray())
            throw new IllegalArgumentException("Unsupported recipient index structure/version");
        List<RecipientIndexRecord> members = new ArrayList<>();
        List<RecipientIndexRecord> subgroups = new ArrayList<>();
        Set<Long> memberIds = new HashSet<>(), subgroupIds = new HashSet<>();
        for (JsonNode node : root.path("records")) {
            String type = requiredText(node, "type");
            long id = requiredLong(node, "id");
            RecipientIndexRecord record = new RecipientIndexRecord(type, id, requiredText(node, "name"),
                    requiredText(node, "username"), requiredText(node, "description"), requiredText(node, "search_text"),
                    stringList(node.path("tokens"), "tokens"), stringList(node.path("source_tokens"), "source_tokens"));
            if ("member".equals(type)) {
                if (!memberIds.add(id)) throw new IllegalArgumentException("Duplicate member ID in index");
                members.add(record);
            } else if ("subgroup".equals(type)) {
                if (!subgroupIds.add(id)) throw new IllegalArgumentException("Duplicate subgroup ID in index");
                subgroups.add(record);
            } else throw new IllegalArgumentException("Unknown recipient index record type");
        }
        JsonNode counts = root.path("counts");
        if (counts.path("members").asInt(-1) != members.size()
                || counts.path("subgroups").asInt(-1) != subgroups.size()
                || counts.path("records").asInt(-1) != members.size() + subgroups.size())
            throw new IllegalArgumentException("Recipient index counts do not match its records");
        Map<String, List<String>> aliases = parseAliases(root.path("verified_or_strongly_supported_aliases"));
        return new Snapshot(List.copyOf(members), List.copyOf(subgroups), aliases,
                makeInvertedIndex(members), makeInvertedIndex(subgroups), Instant.now());
    }

    private static Map<String, List<String>> parseAliases(JsonNode aliasesNode) {
        if (!aliasesNode.isObject()) throw new IllegalArgumentException("Verified aliases must be an object");
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliasesNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) throw new IllegalArgumentException("Alias values must be arrays");
            List<String> values = new ArrayList<>();
            values.add(entry.getKey());
            entry.getValue().forEach(value -> {
                if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Aliases must be non-empty strings");
                values.add(value.asText());
            });
            aliases.put(entry.getKey(), List.copyOf(values.stream().distinct().toList()));
        });
        return Collections.unmodifiableMap(aliases);
    }

    private static Map<String, List<RecipientIndexRecord>> makeInvertedIndex(List<RecipientIndexRecord> records) {
        Map<String, List<RecipientIndexRecord>> mutable = new HashMap<>();
        for (RecipientIndexRecord record : records) {
            Set<String> tokens = new LinkedHashSet<>();
            record.tokens().forEach(token -> tokens.addAll(AliasService.tokens(token)));
            record.source_tokens().forEach(token -> tokens.addAll(AliasService.tokens(token)));
            tokens.addAll(AliasService.tokens(record.search_text()));
            for (String token : tokens) mutable.computeIfAbsent(token, ignored -> new ArrayList<>()).add(record);
        }
        Map<String, List<RecipientIndexRecord>> immutable = new HashMap<>();
        mutable.forEach((token, rows) -> immutable.put(token, List.copyOf(rows)));
        return Collections.unmodifiableMap(immutable);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Missing or invalid index field: " + field);
        return value.asText();
    }
    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0) throw new IllegalArgumentException("Missing or invalid index field: " + field);
        return value.asLong();
    }
    private static List<String> stringList(JsonNode node, String field) {
        if (!node.isArray()) throw new IllegalArgumentException("Missing or invalid index field: " + field);
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) throw new IllegalArgumentException("Invalid token in index field: " + field);
            values.add(value.asText());
        });
        return List.copyOf(values);
    }
    private static String resolvePath(String path) {
        return path.startsWith("classpath:") || path.startsWith("file:") ? path : "file:" + path;
    }

    public record Snapshot(List<RecipientIndexRecord> members, List<RecipientIndexRecord> subgroups,
                           Map<String, List<String>> verifiedAliases,
                           Map<String, List<RecipientIndexRecord>> memberByToken,
                           Map<String, List<RecipientIndexRecord>> subgroupByToken,
                           Instant loadedAt) { }
    public record IndexStatus(int member_count, int subgroup_count, Instant loaded_at) { }
}
