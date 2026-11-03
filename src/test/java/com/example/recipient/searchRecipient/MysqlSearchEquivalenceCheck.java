package com.example.recipient.searchRecipient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.recipient.searchRecipient.model.ParsedRecipient;
import com.example.recipient.searchRecipient.model.ResolveRecipientRequest;
import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.example.recipient.searchRecipient.service.CatalogEngine;
import com.example.recipient.searchRecipient.service.DeterministicIntentParser;
import com.example.recipient.searchRecipient.service.RecipientDirectory;
import com.example.recipient.searchRecipient.service.RecipientResolverService;

/**
 * Searching through MySQL must give exactly what a full scan of the same list gives. Needs a database loaded with
 * recipient_catalog.sql and recipient_decoded_import.sql; never point it at production:
 * mvn test -Dtest=MysqlSearchEquivalenceCheck -Dmysql.url=jdbc:mysql://localhost:33061/recipient -Dmysql.user=root -Dmysql.password=...
 */
@EnabledIfSystemProperty(named = "mysql.url", matches = ".+")
class MysqlSearchEquivalenceCheck {
    private static final long LIST_ID = Long.getLong("mysql.list", 173);
    private static final List<String> QUERIES = List.of("Send to all SIs in Nalgonda", "SIs in Mulugu", "SIs in MLG",
            "Circle Inspectors in Nalgonda", "DSPs in Nalgonda", "SHOs in Karimnagar", "SHOs in Kamareddy", "CIs in Medak",
            "Head constables in Ramagundam", "ACPs in Malkajgiri", "SHOs in Rachakonda", "Inspectors in Khammam",
            "send to all SHOs in Cyberabad except traffic", "WSIs in Hyderabad", "SHOs in Nalgoda", "trafic SIs",
            "L&O SHOs in Cyberabad", "ahtu", "send to cyber crime", "groups of SIs in Karimnagar", "DSPs except Nalgonda",
            "all sis", "zzzz", "Rames", "acp-abids@suraksha", "admin", "ccrb", "ccps knr", "si1046", "allcis");

    @Test void mysqlSearchMatchesAFullScan() {
        // One reused connection, as the application's pool gives, so timings measure the queries.
        SingleConnectionDataSource source = new SingleConnectionDataSource(System.getProperty("mysql.url"),
                System.getProperty("mysql.user", "root"), System.getProperty("mysql.password", ""), true);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        MysqlRecipientDirectory mysql = new MysqlRecipientDirectory(jdbc, LIST_ID, 120, "");
        CatalogEngine engine = mysql.snapshot(LIST_ID).engine();

        // The same list held in memory: every member and subgroup, parsed by the same code.
        List<ParsedRecipient> members = mysql.members(LIST_ID, engine, "TRUE", List.of(), "FALSE", List.of());
        List<ParsedRecipient> subgroups = jdbc.query("SELECT id, sub_group_name, sub_group_desc FROM list_sub_groups WHERE list_id = ?",
                (row, i) -> engine.parser().subgroup(row.getLong(1), row.getString(2), row.getString(3)), LIST_ID);
        RecipientDirectory.Snapshot memory = RecipientDirectory.Snapshot.of(LIST_ID, engine, members, subgroups);
        RecipientDirectory inMemory = new RecipientDirectory() {
            public RecipientDirectory.View snapshot(Long listId) { return memory; }
            public Status reload() { return memory.status(); }
        };

        // Fixed queries, then the words and full names of random members.
        Set<String> queries = new LinkedHashSet<>(System.getProperty("check.only") == null ? QUERIES : List.of(System.getProperty("check.only")));
        Random random = new Random(11);
        for (int i = 0; i < Integer.getInteger("check.random", 150); i++) {
            ParsedRecipient member = members.get(random.nextInt(members.size()));
            queries.add(member.name());
            queries.add(member.name().replaceFirst("@.*", "").replace('-', ' '));
        }

        RecipientResolverService scan = new RecipientResolverService(new DeterministicIntentParser(), inMemory, 200);
        RecipientResolverService db = new RecipientResolverService(new DeterministicIntentParser(), mysql, 200);
        long scanNanos = 0, dbNanos = 0;
        int matched = 0;
        for (String query : queries) {
            ResolveRecipientRequest request = new ResolveRecipientRequest(query, 200, 0);
            long t0 = System.nanoTime();
            ResolveRecipientResponse expected = scan.resolve(request);
            long t1 = System.nanoTime();
            ResolveRecipientResponse actual = db.resolve(request);
            long t2 = System.nanoTime();
            scanNanos += t1 - t0;
            dbNanos += t2 - t1;
            assertEquals(summary(expected), summary(actual), query);
            if (QUERIES.contains(query)) System.out.printf("CHECK query=%-46s members=%6d scan_ms=%5d mysql_ms=%5d%n",
                    "'" + query + "'", actual.member_total(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
            matched += expected.member_total();
        }
        System.out.printf("CHECK queries=%d members=%d matched=%d scan_ms=%d mysql_ms=%d%n", queries.size(), members.size(),
                matched, scanNanos / 1_000_000, dbNanos / 1_000_000);
    }

    private static List<String> summary(ResolveRecipientResponse response) {
        List<String> out = new ArrayList<>();
        out.add("members=" + response.member_total() + " subgroups=" + response.subgroup_total()
                + " clarification=" + response.clarification());
        response.member_matches().forEach(m -> out.add(m.id() + " " + m.status() + " " + m.match_type() + " " + m.match_reason()));
        response.subgroup_matches().forEach(m -> out.add(m.id() + " " + m.status() + " " + m.match_type() + " " + m.match_reason()));
        return out;
    }
}
