package com.example.recipient.searchRecipient.service;

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

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.searchRecipient.model.Intent.RecipientType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiIntentParser implements IntentParser {
    private static final Logger log = LoggerFactory.getLogger(OpenAiIntentParser.class);
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;
    private final String temperature;
    private final String apiKey;

    public OpenAiIntentParser(RestClient.Builder builder, ObjectMapper mapper,
                              @Value("${llm.api-key:${OPENAI_API_KEY:${LLM_API_KEY:}}}") String key,
                              @Value("${llm.model:gpt-4.1-mini}") String model,
                           @Value("${llm.temperature:}") String temperature) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.apiKey = key;
        this.client = builder.requestFactory(requestFactory).baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + key).build();
        this.mapper = mapper; this.model = model; this.temperature = temperature;
        if (key.isBlank()) log.warn("intent.provider.configured provider=openai model={} api_key_present=false; deterministic fallback will be used", model);
        else log.info("intent.provider.configured provider=openai model={} api_key_present=true", model);
    }

    @Override
    public Intent parse(String query) {
        if (apiKey.isBlank()) return deterministic(query, "missing_api_key");
        try {
            JsonNode request = mapper.createObjectNode().put("model", model)
                    .set("input", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("role", "system").put("content", SYSTEM_PROMPT))
                            .add(mapper.createObjectNode().put("role", "user").put("content", query)));
            ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("store", false);
            // Some models reject temperature, so it is sent only when llm.temperature is set.
            if (!temperature.isBlank())
                ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("temperature", Double.parseDouble(temperature));
            ((com.fasterxml.jackson.databind.node.ObjectNode) request).set("text", mapper.readTree(OUTPUT_SCHEMA));

            JsonNode response = client.post().uri("/responses").contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
            String output = response.path("output").findValuesAsText("text").stream().findFirst().orElseThrow();
            JsonNode intentNode = mapper.readTree(output);
            List<String> terms = texts(intentNode.path("search_terms"));
            List<String> excluded = texts(intentNode.path("exclude_terms"));
            RecipientType recipientType = parseRecipientType(intentNode.path("recipient_type").asText());
            log.info("intent.openai.complete search_terms={} exclude_terms={} scope={} recipient_type={}",
                    terms.size(), excluded.size(), intentNode.path("scope").asText(), recipientType);
            return new Intent(query, terms, excluded, "ALL".equals(intentNode.path("scope").asText()), recipientType);
        } catch (Exception ex) {
            log.warn("intent.openai.failed error_type={}", ex.getClass().getSimpleName());
            return deterministic(query, ex.getClass().getSimpleName());
        }
    }

    private Intent deterministic(String query, String reason) {
        log.warn("intent.parse.fallback reason={}", reason);
        return DeterministicIntentParser.parseQuery(query);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> {
            String value = node.asText("").trim();
            if (!value.isEmpty()) values.add(value);
        });
        return values;
    }

    private static RecipientType parseRecipientType(String value) {
        try { return RecipientType.valueOf(value); }
        catch (IllegalArgumentException ex) { return RecipientType.AUTO; }
    }

    public static final String SEARCH_RULES = """
            The directory is a Telangana police messaging list. Members are coded usernames such as "si-ahtu-nlg" \
            (designation-unit-district) and groups have names and descriptions such as "ALLCIs-NLG". The application \
            maps abbreviations, district codes and spelling variants itself.
            - Put each searchable item in search_terms as a separate entry: designations or ranks (SI, CI, SHO, DSP, \
            ACP, Inspector...), locations (districts, commissionerates, zones, sub-divisions, police stations), units or \
            wings (traffic, ITC, CCS...), person names, usernames and codes.
            - Preserve the user's wording, spelling, abbreviations and codes. Do not expand abbreviations, translate, \
            correct spelling, or add words the user did not say.
            - Do not include command words ("send", "select", "find", "please") or generic words ("all", "officers", \
            "people", "members", "groups") in search_terms.
            - Put anything the user wants left out ("except traffic", "not Suryapet") in exclude_terms, never in search_terms.
            - recipient_type: MEMBERS for individuals, GROUPS when the user asks for groups, BOTH when both are \
            requested, AUTO when unclear.
            - scope: ALL only if the user explicitly says all, every, or each. Otherwise SOME or UNSPECIFIED.
            """;

    private static final String SYSTEM_PROMPT = "You extract recipient search filters from a request to message people.\n"
            + SEARCH_RULES + """
            Return only the required JSON.
            Examples:
            "Select all CI's in Nalgonda" -> search_terms ["CI's", "Nalgonda"], exclude_terms [], scope ALL
            "Find Srikanth" -> search_terms ["Srikanth"]
            "All SHOs in Cyberabad except traffic" -> search_terms ["SHOs", "Cyberabad"], exclude_terms ["traffic"], scope ALL
            "Select all groups in Nalgonda" -> search_terms ["Nalgonda"], recipient_type GROUPS, scope ALL
            "Search si-ahtu-nlg" -> search_terms ["si-ahtu-nlg"]
            """;

    private static final String OUTPUT_SCHEMA = """
            {"format":{"type":"json_schema","name":"recipient_intent","strict":true,"schema":{"type":"object","additionalProperties":false,"properties":{"search_terms":{"type":"array","items":{"type":"string"}},"exclude_terms":{"type":"array","items":{"type":"string"}},"recipient_type":{"type":"string","enum":["MEMBERS","GROUPS","BOTH","AUTO"]},"scope":{"type":"string","enum":["ALL","SOME","UNSPECIFIED"]}},"required":["search_terms","exclude_terms","recipient_type","scope"]}}}
            """;
}
