package com.example.recipient.searchRecipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.service.CatalogNotConfiguredException;
import com.example.recipient.searchRecipient.service.RecipientCatalog;

class MysqlRecipientDirectoryTest {
    private JdbcTemplate jdbc;

    @BeforeEach void setUp() { jdbc = mock(JdbcTemplate.class); }

    @SuppressWarnings("unchecked")
    private void catalogRows(long listId, List<RecipientCatalog.Phrase> phrases) {
        when(jdbc.query(eq(MysqlRecipientDirectory.PHRASE_SQL), any(RowMapper.class), eq(listId))).thenReturn(phrases);
    }

    private void queryWords(long listId, String... wordCategoryPairs) {
        org.mockito.Mockito.doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (int i = 0; i < wordCategoryPairs.length; i += 2) {
                ResultSet row = mock(ResultSet.class);
                when(row.getString("word")).thenReturn(wordCategoryPairs[i]);
                when(row.getString("category")).thenReturn(wordCategoryPairs[i + 1]);
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(eq(MysqlRecipientDirectory.WORD_SQL), any(RowCallbackHandler.class), eq(listId));
    }

    @Test void listWithoutCatalogRowsFailsInsteadOfGuessing() {
        catalogRows(7L, List.of());
        queryWords(7L);
        MysqlRecipientDirectory directory = new MysqlRecipientDirectory(jdbc, 173, 120, "");
        CatalogNotConfiguredException error = assertThrows(CatalogNotConfiguredException.class, () -> directory.snapshot(7L));
        assertEquals(7L, error.listId());
    }

    @SuppressWarnings("unchecked")
    @Test void eachListIsLoadedWithItsOwnCatalogAndCached() {
        RecipientCatalog defaults = TestIndexFactory.catalog();
        catalogRows(173L, defaults.rows());
        queryWords(173L, "send", "COMMAND", "in", "FILLER");
        // List 120 only knows "SI"; it has no locations, so "Nalgonda" stays a plain keyword there.
        catalogRows(120L, List.of(new RecipientCatalog.Phrase(RecipientCatalog.Kind.DESIGNATION, "SI", "Sub-Inspector", "si", true)));
        queryWords(120L, "send", "COMMAND");
        MysqlRecipientDirectory directory = new MysqlRecipientDirectory(jdbc, 173, 120, "");

        var defaultList = directory.snapshot(null);
        var otherList = directory.snapshot(120L);
        assertEquals(173L, defaultList.listId());
        assertEquals(120L, otherList.listId());
        assertEquals(java.util.Set.of("Nalgonda"), defaultList.engine().catalog()
                .annotate(List.of("nalgonda")).locations().keySet());
        assertEquals(List.of("nalgonda"), otherList.engine().catalog().annotate(List.of("nalgonda")).rest());

        assertSame(defaultList.engine(), directory.snapshot(173L).engine(), "catalog cached within the TTL");
        verify(jdbc, times(1)).query(eq(MysqlRecipientDirectory.PHRASE_SQL), any(RowMapper.class), eq(173L));
    }
}
