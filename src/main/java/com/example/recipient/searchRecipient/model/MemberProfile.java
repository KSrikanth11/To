package com.example.recipient.searchRecipient.model;

import java.util.List;
import java.util.Map;

/**
 * A decoded member (recipient_member_profile + recipient_member_attribute). designations and locations map
 * a catalog key to whether that value is verified; texts are the decoded descriptions, read as keywords.
 */
public record MemberProfile(long memberId, String username, String confidence, List<String> texts,
                            Map<String, Boolean> designations, Map<String, Boolean> locations) {
    public static final String SYSTEM = "SYSTEM";
}
