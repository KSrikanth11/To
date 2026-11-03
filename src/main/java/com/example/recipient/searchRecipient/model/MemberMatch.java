package com.example.recipient.searchRecipient.model;

import java.util.List;

/** status is CONFIRMED (safe to auto-select) or SUGGESTED (show to the user, do not auto-select). */
public record MemberMatch(long id, String list_username, String status, String match_type,
                          List<String> matched_terms, String match_reason, double score) { }
