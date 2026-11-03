package com.example.recipient;

import com.example.recipient.searchRecipient.service.CatalogEngine;
import com.example.recipient.searchRecipient.service.DeterministicIntentParser;
import com.example.recipient.searchRecipient.service.IndexRecipientDirectory;
import com.example.recipient.searchRecipient.service.RecipientAliasDictionary;
import com.example.recipient.searchRecipient.service.RecipientCatalog;
import com.example.recipient.searchRecipient.service.RecipientDirectory;
import com.example.recipient.searchRecipient.service.RecipientResolverService;
import com.example.recipient.searchRecipient.service.RecipientSearchIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

public final class TestIndexFactory {
    private static final String INDEX = "classpath:suraksha_recipient_search_index.json";

    private TestIndexFactory() { }

    public static RecipientSearchIndex load() {
        RecipientSearchIndex index = new RecipientSearchIndex(new DefaultResourceLoader(), new ObjectMapper(), INDEX);
        index.initialize();
        return index;
    }

    public static RecipientCatalog catalog() { return catalog(""); }

    public static RecipientCatalog catalog(String customLocations) {
        return catalog(customLocations, "");
    }

    public static RecipientCatalog catalog(String customLocations, String locationParents) {
        RecipientAliasDictionary dictionary = new RecipientAliasDictionary(new DefaultResourceLoader(), new ObjectMapper(), INDEX);
        dictionary.load();
        return RecipientCatalog.fromDefaults(dictionary.aliases(), dictionary.unverifiedAliases(), customLocations, "", "",
                locationParents);
    }

    public static RecipientDirectory directory(RecipientCatalog catalog) {
        return new IndexRecipientDirectory(load(), CatalogEngine.of(catalog));
    }

    public static RecipientResolverService resolver(RecipientDirectory directory) {
        return new RecipientResolverService(new DeterministicIntentParser(), directory, 200);
    }

    public static RecipientResolverService resolver() {
        return resolver(directory(catalog()));
    }
}
