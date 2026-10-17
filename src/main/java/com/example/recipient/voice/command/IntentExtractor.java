package com.example.recipient.voice.command;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.recipient.searchRecipient.model.Intent;
import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.ComposeFormState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class IntentExtractor {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;

    public IntentExtractor(RestClient.Builder builder, ObjectMapper mapper,
                           @Value("${llm.api-key:${OPENAI_API_KEY:${LLM_API_KEY:}}}") String key,
                           @Value("${llm.model:gpt-4.1-mini}") String model) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.client = builder.requestFactory(requestFactory).baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + key).build();
        this.mapper = mapper;
        this.model = model;
    }

    public CommandIntent extract(String transcript, ComposeFormState form) {
        try {
            JsonNode input = mapper.createObjectNode()
                    .put("transcript", transcript)
                    .set("form", mapper.valueToTree(form == null ? ComposeFormState.empty() : form));
            JsonNode request = mapper.createObjectNode().put("model", model)
                    .set("input", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("role", "system").put("content", SYSTEM_PROMPT))
                            .add(mapper.createObjectNode().put("role", "user").put("content", mapper.writeValueAsString(input))));
            ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("store", false);
            ((com.fasterxml.jackson.databind.node.ObjectNode) request).set("text", mapper.readTree(OUTPUT_SCHEMA));

            JsonNode response = client.post().uri("/responses").contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(JsonNode.class);
            String output = response.path("output").findValuesAsText("text").stream().findFirst().orElseThrow();
            JsonNode node = mapper.readTree(output);

            List<String> searchTerms = new java.util.ArrayList<>();
            node.path("search_terms").forEach(term -> searchTerms.add(term.asText()));
            Intent.RecipientType recipientType = recipientType(node.path("recipient_type").asText());
            CommandIntent.IntentType intentType = intentType(node.path("intent").asText());
            String text = node.path("text").isNull() ? null : node.path("text").asText();
            String field = node.path("field").isNull() ? null : node.path("field").asText();
            List<String> labels = new java.util.ArrayList<>();
            node.path("labels").forEach(label -> labels.add(label.asText()));
            return new CommandIntent(intentType, searchTerms, recipientType, node.path("scope").asText(),
                    text, labels, field, node.path("confidence").asDouble());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not extract voice command intent", ex);
        }
    }

    private static Intent.RecipientType recipientType(String value) {
        try { return Intent.RecipientType.valueOf(value); }
        catch (IllegalArgumentException ex) { return Intent.RecipientType.AUTO; }
    }

    private static CommandIntent.IntentType intentType(String value) {
        try { return CommandIntent.IntentType.valueOf(value); }
        catch (IllegalArgumentException ex) { return CommandIntent.IntentType.UNKNOWN; }
    }

    private static final String SYSTEM_PROMPT = """
            You are a voice-command interpreter for a messaging application. The user speaks or types one command at a time. Your job is to decide what the user wants to do, and extract only the data needed for that action. You do not perform actions, answer questions, or write content yourself.

            The input contains the user's transcript and, optionally, the current compose form state (subject, message, labels, recipient count). Use the form state only to resolve relative commands such as "add", "change", "clear" or "it". Never copy form state into your output.

            STEP 1: Choose exactly one intent.
            - SEARCH_RECIPIENTS: the user wants to find or select people or groups.
            - SET_MESSAGE: the user wants to write or replace the message body.
            - APPEND_MESSAGE: the user wants to add more text to an existing message.
            - SET_SUBJECT: the user wants to set or change the subject.
            - ADD_LABEL: the user wants to attach one or more labels.
            - REMOVE_LABEL: the user wants to detach one or more labels.
            - CLEAR_FIELD: the user wants to empty the subject, message, labels or recipients.
            - SEND: the user wants to send the message.
            - UNKNOWN: the input is unclear, unrelated, or fits none of the above. Prefer UNKNOWN over guessing.

            Intent hints (the user may speak English, Telugu, or a mix, including transliterated Telugu):
            - "write a message", "message is", "tell them" -> SET_MESSAGE
            - "add to message", "also say", "append" -> APPEND_MESSAGE, or SET_MESSAGE if the form message is empty
            - "subject is", "subject", "title" -> SET_SUBJECT
            - "add label", "tag as", "mark as" -> ADD_LABEL
            - "remove label", "untag" -> REMOVE_LABEL
            - "clear", "delete", "remove the subject/message" -> CLEAR_FIELD
            - "send it", "send now", "go ahead and send" -> SEND
            - Anything naming people, roles, places, or groups to select -> SEARCH_RECIPIENTS

            STEP 2: Fill only the fields that belong to the chosen intent. Set every other field to null, [] or its neutral value.

            SEARCH_RECIPIENTS rules:
            - Put the searchable words in search_terms: names, titles, roles, locations, departments, organizations, groups, categories, identifiers, codes.
            - Do not rely on any predefined domain, vocabulary, or list of known terms.
            - Preserve the user's original wording, spelling, abbreviations, and identifiers.
            - Extract each independent term or phrase as a separate item. Keep multiword names or identifiers together only when they form one name.
            - Do not extract operational words such as "select", "find", "show", "please" unless they are themselves search values.
            - Do not invent terms, expand abbreviations, translate, or infer values.
            - Do not discard an unfamiliar word just because you do not know what it means.
            - recipient_type: MEMBERS for individuals, GROUPS for collectives, BOTH when both are requested or all matching options are requested, AUTO when unclear. Never use it to decide what is searchable.
            - scope: ALL only if the user explicitly says all, every, or each. Otherwise SOME or UNSPECIFIED.

            SET_MESSAGE, APPEND_MESSAGE, SET_SUBJECT rules:
            - Put the dictated content in "text", copied exactly as the user said it, after removing only the command phrase ("write a message", "subject is", etc.).
            - Do not rephrase, correct, translate, shorten, expand, or improve the text.
            - Do not interpret commands that appear inside the content. If the user says "write a message send the report to Ravi", the text is "send the report to Ravi" and the intent is SET_MESSAGE, not SEND.

            ADD_LABEL, REMOVE_LABEL rules:
            - Put each label name in "labels" as spoken. Do not normalize, translate or invent labels.

            CLEAR_FIELD rules:
            - Set "field" to one of SUBJECT, MESSAGE, LABELS, RECIPIENTS.

            SEND rules:
            - No other fields are needed. Never assume confirmation.

            STEP 3: Set confidence from 0.0 to 1.0. Use below 0.6 when the intent is ambiguous, the transcript looks garbled, or the command could mean two things.

            Return only the required JSON. Do not explain your reasoning.

            Examples:
            "Select all CI's in Nalgonda" -> intent SEARCH_RECIPIENTS, search_terms ["CI's","Nalgonda"], recipient_type MEMBERS, scope ALL
            "Find Srikanth" -> intent SEARCH_RECIPIENTS, search_terms ["Srikanth"]
            "Show accounts team in Hyderabad" -> intent SEARCH_RECIPIENTS, search_terms ["accounts","team","Hyderabad"]
            "Search ABC-123" -> intent SEARCH_RECIPIENTS, search_terms ["ABC-123"]
            "Select all groups in Nalgonda" -> intent SEARCH_RECIPIENTS, search_terms ["groups","Nalgonda"], recipient_type GROUPS, scope ALL
            "Write a message hi everyone, meeting at five" -> intent SET_MESSAGE, text "hi everyone, meeting at five"
            "Also add please confirm" (form message not empty) -> intent APPEND_MESSAGE, text "please confirm"
            "Subject is monthly review" -> intent SET_SUBJECT, text "monthly review"
            "Add label urgent" -> intent ADD_LABEL, labels ["urgent"]
            "Remove label urgent" -> intent REMOVE_LABEL, labels ["urgent"]
            "Clear the subject" -> intent CLEAR_FIELD, field SUBJECT
            "Send it" -> intent SEND
            "What's the weather" -> intent UNKNOWN, confidence 0.9
            """;

    private static final String OUTPUT_SCHEMA = """
            {"format":{"type":"json_schema","name":"voice_command_intent","strict":true,"schema":{"type":"object","additionalProperties":false,"properties":{"intent":{"type":"string","enum":["SEARCH_RECIPIENTS","SET_MESSAGE","APPEND_MESSAGE","SET_SUBJECT","ADD_LABEL","REMOVE_LABEL","CLEAR_FIELD","SEND","UNKNOWN"]},"search_terms":{"type":"array","items":{"type":"string"}},"recipient_type":{"type":"string","enum":["MEMBERS","GROUPS","BOTH","AUTO"]},"scope":{"type":"string","enum":["ALL","SOME","UNSPECIFIED"]},"text":{"type":["string","null"]},"labels":{"type":"array","items":{"type":"string"}},"field":{"type":["string","null"],"enum":["SUBJECT","MESSAGE","LABELS","RECIPIENTS",null]},"confidence":{"type":"number"}},"required":["intent","search_terms","recipient_type","scope","text","labels","field","confidence"]}}}
            """;
}
