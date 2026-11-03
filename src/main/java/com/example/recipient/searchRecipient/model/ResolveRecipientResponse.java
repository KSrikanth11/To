package com.example.recipient.searchRecipient.model;

import java.util.List;

public record ResolveRecipientResponse(String query, List<MemberMatch> member_matches,
                                       List<SubgroupMatch> subgroup_matches,
                                       boolean needs_clarification, String clarification,
                                       boolean has_more, Integer next_offset,
                                       Intent.RecipientType recipient_type,
                                       int member_total, int subgroup_total,
                                       Interpretation interpretation) {

    /** How the query was read, so the UI can show "SI · Nalgonda" and the user can spot a misreading. */
    public record Interpretation(List<String> designations, List<String> locations, List<String> keywords,
                                 List<String> excluded, List<String> unresolved_terms) {
        public static Interpretation empty() {
            return new Interpretation(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
