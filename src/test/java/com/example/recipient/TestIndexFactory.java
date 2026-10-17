package com.example.recipient;

import com.example.recipient.service.RecipientSearchIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

public final class TestIndexFactory {
    private TestIndexFactory() { }
    public static RecipientSearchIndex load() {
        RecipientSearchIndex index = new RecipientSearchIndex(new DefaultResourceLoader(), new ObjectMapper(),
                "classpath:suraksha_recipient_search_index.json");
        index.initialize();
        return index;
    }
}
