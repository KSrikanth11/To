package com.example.recipient.model;

import java.util.List;

public record ResolveRecipientResponse(String query, List<MemberMatch> member_matches,
                                       List<SubgroupMatch> subgroup_matches,
                                       boolean needs_clarification, boolean has_more, Integer next_offset,
                                       Intent.RecipientType recipient_type) { }
