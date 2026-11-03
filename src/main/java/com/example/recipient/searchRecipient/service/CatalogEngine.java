package com.example.recipient.searchRecipient.service;

/** One list's catalog and the parser, interpreter and matcher that read through it. */
public record CatalogEngine(RecipientCatalog catalog, RecipientParser parser, QueryInterpreter interpreter,
                            RecipientMatcher matcher) {
    public static CatalogEngine of(RecipientCatalog catalog) {
        return new CatalogEngine(catalog, new RecipientParser(catalog), new QueryInterpreter(catalog),
                new RecipientMatcher(catalog));
    }
}
