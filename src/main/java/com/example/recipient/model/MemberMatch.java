package com.example.recipient.model;

import java.util.List;

public record MemberMatch(long id, String list_username, String match_type,
                          List<String> matched_terms, String match_reason, double score) { }
