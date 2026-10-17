package com.example.recipient.searchRecipient.service;

import com.example.recipient.TestIndexFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecipientSearchIndexTest {
    @TempDir Path directory;

    @Test void failedReloadKeepsTheLastGoodSnapshotActive() throws Exception {
        Path indexFile = directory.resolve("index.json");
        String source = Files.readString(Path.of("src/main/resources/suraksha_recipient_search_index.json"));
        Files.writeString(indexFile, source);
        RecipientSearchIndex index = new RecipientSearchIndex(new DefaultResourceLoader(), new ObjectMapper(),
                indexFile.toUri().toString());
        index.initialize();
        var previous = index.snapshot();

        Files.writeString(indexFile, "{\"version\":999}");
        assertThrows(IllegalStateException.class, index::reload);
        assertSame(previous, index.snapshot());
    }
}
