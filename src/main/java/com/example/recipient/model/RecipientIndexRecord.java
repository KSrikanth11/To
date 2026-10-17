package com.example.recipient.model;

import java.util.List;

/** Fields match records[] in suraksha_recipient_search_index.json. */
public record RecipientIndexRecord(String type, long id, String name, String username,
                                   String description, String search_text,
                                   List<String> tokens, List<String> source_tokens) { }
