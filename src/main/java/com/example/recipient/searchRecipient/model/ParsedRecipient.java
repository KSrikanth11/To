package com.example.recipient.searchRecipient.model;

import java.util.Map;
import java.util.Set;

/**
 * A directory row read into fields: designations and locations (value = backed by verified evidence), and
 * every other token as a keyword. Built from a decoded member profile when one exists, otherwise by
 * reading the username or group name/description with the list's catalog.
 */
public record ParsedRecipient(String type, long id, String name, String description,
                              Map<String, Boolean> designations, Map<String, Boolean> locations,
                              Set<String> keywords, String exactKey) {
    public boolean isSubgroup() { return "subgroup".equals(type); }
}
