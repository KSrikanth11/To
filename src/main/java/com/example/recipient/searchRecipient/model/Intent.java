package com.example.recipient.searchRecipient.model;

import java.util.List;

/** What the user asked for, as extracted terms; the catalog decides what each term means. */
public record Intent(String originalQuery, List<String> searchTerms, List<String> excludeTerms,
                     boolean allScope, RecipientType recipientType) {
    public Intent(String originalQuery, List<String> searchTerms, boolean allScope, RecipientType recipientType) {
        this(originalQuery, searchTerms, List.of(), allScope, recipientType);
    }

    public Intent {
        searchTerms = searchTerms == null ? List.of() : List.copyOf(searchTerms);
        excludeTerms = excludeTerms == null ? List.of() : List.copyOf(excludeTerms);
        recipientType = recipientType == null ? RecipientType.AUTO : recipientType;
    }

    public enum RecipientType { MEMBERS, GROUPS, BOTH, AUTO }
}
