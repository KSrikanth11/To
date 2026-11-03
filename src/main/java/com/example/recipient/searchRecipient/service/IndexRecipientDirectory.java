package com.example.recipient.searchRecipient.service;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Offline directory backed by the JSON index, with the built-in catalog defaults and the JSON alias
 * dictionary. listId is ignored because the file holds one list.
 */
@Service
@ConditionalOnProperty(name = "resolver.source", havingValue = "index", matchIfMissing = true)
public class IndexRecipientDirectory implements RecipientDirectory {
    private static final long INDEX_LIST_ID = 0;
    private final RecipientSearchIndex index;
    private final CatalogEngine engine;
    private final AtomicReference<Parsed> parsed = new AtomicReference<>();

    @Autowired
    public IndexRecipientDirectory(RecipientSearchIndex index, RecipientAliasDictionary dictionary,
                                   @Value("${resolver.custom-aliases:}") String customLocations,
                                   @Value("${resolver.custom-designations:}") String customDesignations,
                                   @Value("${resolver.custom-units:}") String customUnits,
                                   @Value("${resolver.location-parents:}") String locationParents) {
        this(index, CatalogEngine.of(RecipientCatalog.fromDefaults(dictionary.aliases(), dictionary.unverifiedAliases(),
                customLocations, customDesignations, customUnits, locationParents)));
    }

    public IndexRecipientDirectory(RecipientSearchIndex index, CatalogEngine engine) {
        this.index = index;
        this.engine = engine;
    }

    @Override
    public Snapshot snapshot(Long ignoredListId) {
        RecipientSearchIndex.Snapshot source = index.snapshot();
        Parsed current = parsed.get();
        if (current != null && current.source() == source) return current.snapshot();
        RecipientParser parser = engine.parser();
        Snapshot snapshot = Snapshot.of(INDEX_LIST_ID, engine,
                source.members().stream().map(r -> parser.member(r.id(), r.username())).toList(),
                source.subgroups().stream().map(r -> parser.subgroup(r.id(), r.name(), r.description())).toList());
        parsed.set(new Parsed(source, snapshot));
        return snapshot;
    }

    @Override
    public Status reload() {
        index.reload();
        return snapshot(null).status();
    }

    private record Parsed(RecipientSearchIndex.Snapshot source, Snapshot snapshot) { }
}
