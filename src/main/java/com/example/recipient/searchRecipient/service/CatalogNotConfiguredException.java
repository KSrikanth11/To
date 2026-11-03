package com.example.recipient.searchRecipient.service;

/** The list has no rows in the recipient_* catalog tables, so its queries cannot be interpreted safely. */
public class CatalogNotConfiguredException extends RuntimeException {
    private final long listId;

    public CatalogNotConfiguredException(long listId) {
        super("Recipient catalog is not configured for list " + listId);
        this.listId = listId;
    }

    public long listId() { return listId; }
}
