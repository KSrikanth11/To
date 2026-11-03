package com.example.recipient.searchRecipient.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared normalization so query text and directory rows are tokenized identically. */
public final class TextNormalizer {
    /** Letter code followed by a sequence number, such as si1046, ci1 or admin2. */
    private static final Pattern CODE_SUFFIX = Pattern.compile("^([a-z]+)\\d+$");

    private TextNormalizer() { }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\bl\\s*&\\s*o\\b", " lo ")
                .replaceAll("\\blaw\\s+(?:and|&)\\s+order\\b", " lo ")
                .replaceAll("'s\\b", "s")
                .replaceAll("[^\\p{Alnum}]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** Ordered tokens; duplicates are kept so multi-word phrases stay adjacent. */
    public static List<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return List.of();
        return Arrays.asList(normalized.split(" "));
    }

    /** Removes a trailing mail domain such as "@suraksha" from usernames. */
    public static String stripDomain(String value) {
        return value == null ? "" : value.trim().replaceFirst("@[^@\\s]*$", "");
    }

    /** Returns the letter prefix of a coded token (si1046 -> si), or null. */
    public static String codePrefix(String token) {
        Matcher matcher = CODE_SUFFIX.matcher(token);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Normalized edit-distance similarity in [0, 1]. */
    public static double similarity(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length());
    }
}
