package com.example.recipient.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveRecipientRequest(
        @NotBlank @Size(max = 500) String query,
        @Min(1) @Max(200) Integer limit,
        @Min(0) @Max(10000) Integer offset,
        @Min(1) Long listId
) {
    public ResolveRecipientRequest(String query, Integer limit, Integer offset) {
        this(query, limit, offset, null);
    }

    public int effectiveLimit() { return limit == null ? 50 : limit; }
    public int effectiveOffset() { return offset == null ? 0 : offset; }
}
