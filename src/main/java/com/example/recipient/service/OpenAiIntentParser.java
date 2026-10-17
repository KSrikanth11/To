package com.example.recipient.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.recipient.model.Intent;
import com.example.recipient.model.Intent.RecipientType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiIntentParser implements IntentParser {
    private static final Logger log = LoggerFactory.getLogger(OpenAiIntentParser.class);
    private final RestClient client;
    private final ObjectMapper mapper;
    private final AliasService aliases;
    private final String model;
    private final String apiKey;

    public OpenAiIntentParser(RestClient.Builder builder, ObjectMapper mapper, AliasService aliases,
                              @Value("${llm.api-key:${OPENAI_API_KEY:${LLM_API_KEY:}}}") String key,
                              @Value("${llm.model:gpt-4.1-mini}") String model) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.apiKey = key;
        this.client = builder.requestFactory(requestFactory).baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + key).build();
        this.mapper = mapper; this.aliases = aliases; this.model = model;
        if (key.isBlank()) log.warn("intent.provider.configured provider=openai model={} api_key_present=false; deterministic fallback will be used", model);
        else log.info("intent.provider.configured provider=openai model={} api_key_present=true", model);
    }

    @Override 
    public Intent parse(String query) {
        if (apiKey.isBlank()) return deterministic(query, "missing_api_key");

        log.info("intent.parse.start query=\"{}\" model={}", query, model);
        try {
            JsonNode request = mapper.createObjectNode().put("model", model)
                    .set("input", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("role", "system").put("content", """
                                    You are a generic search-intent extraction engine for a database-backed application.
                                    Extract the meaningful words and phrases from the user's query that could be used to search records in a database.
                                    Rules:
                                    - Identify words or phrases that carry search meaning: names, titles, roles, locations, departments, organizations, groups, categories, identifiers, codes.
                                    - Do not rely on any predefined domain, vocabulary, or list of known terms.
                                    - Preserve the user's original wording, spelling, abbreviations, and identifiers.
                                    - Extract each independent searchable term or phrase as a separate item. Keep multiword names/identifiers together only when they form one name.
                                    - Do not extract operational words like "select", "find", "show", "please".
                                    - Do not invent terms, expand abbreviations, translate, or infer values.
                                    - Do not discard an unfamiliar word just because you don't know what it means.
                                    - recipient_type (only for recipient search): MEMBERS for individuals, GROUPS for collectives, BOTH when both are requested or all matching options are requested, AUTO when unclear. Never use it to decide what is searchable.
                                    - scope: ALL only if the user explicitly says all/every/each; otherwise SOME or UNSPECIFIED.
                                    - Return only the required JSON.
                                    Examples:
                                    "Select all CI's in Nalgonda" -> ["CI's", "Nalgonda"]
                                    "Find Srikanth" -> ["Srikanth"]
                                    "Show accounts team in Hyderabad" -> ["accounts", "team", "Hyderabad"]
                                    "Search ABC-123" -> ["ABC-123"]
                                    "Find the finance department and Ravi" -> ["finance", "department", "Ravi"]
                                    "Select all groups in Nalgonda" -> ["groups", "Nalgonda"]
                                    """))
                            .add(mapper.createObjectNode().put("role", "user").put("content", query)));
            ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("store", false);
            ((com.fasterxml.jackson.databind.node.ObjectNode) request).set("text", mapper.readTree("""
                    {"format":{"type":"json_schema","name":"recipient_intent","strict":true,"schema":{"type":"object","additionalProperties":false,"properties":{"search_terms":{"type":"array","items":{"type":"string"}},"recipient_type":{"type":"string","enum":["MEMBERS","GROUPS","BOTH","AUTO"]},"scope":{"type":"string","enum":["ALL","SOME","UNSPECIFIED"]}},"required":["search_terms","recipient_type","scope"]}}}
                    """));

            JsonNode response = client.post().uri("/responses").contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
            String output = response.path("output").findValuesAsText("text").stream().findFirst().orElseThrow();
            JsonNode intentNode = mapper.readTree(output);
            System.out.println("OpenAI intent parser raw output: " + output);
            List<String> extracted = new ArrayList<>();
            intentNode.path("search_terms").forEach(node -> {
                String term = node.asText("").trim();
                if (!term.isEmpty()) extracted.add(term);
            });
            System.out.println("OpenAI search terms: " + extracted);
            List<Intent.Concept> concepts =
                    new ArrayList<>(aliases.concepts(String.join(" ", extracted)));

            RecipientType recipientType = parseRecipientType(intentNode.path("recipient_type").asText());
            log.info("intent.openai.complete extracted_terms={} alias_expanded_concepts={} scope={} recipient_type={}",
                    extracted, concepts, intentNode.path("scope").asText(), recipientType);

            return new Intent(query, concepts, "ALL".equals(intentNode.path("scope").asText()), recipientType);
        } catch (Exception ex) {
            log.warn("intent.openai.failed query=\"{}\" error_type={}", query, ex.getClass().getSimpleName());
            return deterministic(query, ex.getClass().getSimpleName());
        }
    }
    private Intent deterministic(String query, String reason) {
        List<Intent.Concept> concepts = aliases.concepts(query);
        log.warn("intent.parse.fallback reason={} query=\"{}\" concepts={}", reason, query, concepts);
        boolean allScope = query.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(all|every|each)\\b.*");
        return new Intent(query, concepts, allScope,
                allScope ? RecipientType.BOTH : RecipientType.MEMBERS);
    }

    private static RecipientType parseRecipientType(String value) {
        try { return RecipientType.valueOf(value); }
        catch (IllegalArgumentException ex) { return RecipientType.AUTO; }
    }

}
