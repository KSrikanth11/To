package com.example.recipient.searchRecipient.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.example.recipient.TestIndexFactory;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;

/**
 * Scores CONFIRMED results against docs/evaluation.csv. Rows without expected IDs are skipped, so add
 * reviewed IDs (separated by ';' or '|') to make this a regression gate for precision and recall.
 */
class RecipientEvaluationTest {
    private static final double MIN_PRECISION = 0.98;
    private static final double MIN_RECALL = 0.95;

    @Test void confirmedMatchesMeetPrecisionAndRecallTargets() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("docs/evaluation.csv"));
        RecipientResolverService resolver = TestIndexFactory.resolver();
        List<String> failures = new ArrayList<>();
        int scored = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split(",", -1);
            if (columns.length < 4) continue;
            Set<Long> expected = ids(columns[2]);
            expected.addAll(ids(columns[3]));
            if (expected.isEmpty()) continue;
            scored++;

            var response = resolver.resolve(new ResolveRecipientRequest(columns[0], 200, 0));
            Set<Long> actual = new HashSet<>();
            response.member_matches().stream().filter(m -> RecipientMatcher.CONFIRMED.equals(m.status()))
                    .forEach(m -> actual.add(m.id()));
            response.subgroup_matches().stream().filter(m -> RecipientMatcher.CONFIRMED.equals(m.status()))
                    .forEach(m -> actual.add(m.id()));
            Set<Long> hits = new HashSet<>(actual);
            hits.retainAll(expected);
            double precision = actual.isEmpty() ? 0 : (double) hits.size() / actual.size();
            double recall = (double) hits.size() / expected.size();
            if (precision < MIN_PRECISION || recall < MIN_RECALL)
                failures.add(String.format("'%s' precision=%.2f recall=%.2f", columns[0], precision, recall));
        }
        assumeTrue(scored > 0, "docs/evaluation.csv has no rows with expected IDs yet");
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    private static Set<Long> ids(String column) {
        return Arrays.stream(column.split("[;| ]+")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).collect(Collectors.toCollection(HashSet::new));
    }
}
