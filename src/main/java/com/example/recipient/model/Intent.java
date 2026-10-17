package com.example.recipient.model;

import java.util.List;

public record Intent(String originalQuery, List<Concept> concepts, boolean allScope, RecipientType recipientType) {
    public Intent(String originalQuery, List<Concept> concepts, boolean allScope) {
        this(originalQuery, concepts, allScope, RecipientType.MEMBERS);
    }

    public Intent {
        recipientType = recipientType == null ? RecipientType.AUTO : recipientType;
    }

    public enum RecipientType { MEMBERS, GROUPS, BOTH, AUTO }

    public record Concept(String label, List<String> alternatives) { }
}
