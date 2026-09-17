/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.yasakawa.faskw.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardStoreTest {

    @Test
    void heartbeatUpdatesCollectionHealthWithoutCreatingAnEvent() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-heartbeat;DB_CLOSE_DELAY=-1")) {
            store.recordHeartbeat("node-01", "1.2.7", 256);

            final Map<String, Object> first = store.ingestHealth().getFirst();
            assertEquals("node-01", first.get("nodeId"));
            assertNotNull(first.get("lastReceivedAt"));
            assertNull(first.get("lastOccurredAt"));
            assertEquals(0L, first.get("acceptedCount"));
            assertEquals(256L, first.get("agentSpoolBytes"));

            final Instant occurredAt = Instant.parse("2026-07-31T00:00:00Z");
            store.ingest(List.of(
                    event("9".repeat(64), occurredAt, "VERIFY", "OK")), "1.2.7");
            store.recordHeartbeat("node-01", "1.2.7", 0);

            final Map<String, Object> second = store.ingestHealth().getFirst();
            assertEquals(occurredAt, second.get("lastOccurredAt"));
            assertEquals(1L, second.get("acceptedCount"));
            assertEquals(0L, second.get("agentSpoolBytes"));
        }
    }

    @Test
    void accessAuditUsesRetentionAndRegexQueriesHaveAHardCandidateLimit() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-security-bounds;DB_CLOSE_DELAY=-1")) {
            store.recordAccess("admin", "viewer", "events", "all", "OK", "127.0.0.1");

            assertEquals(1L, store.deleteAccessAuditOlderThan(Instant.now().plusSeconds(1)));
            assertEquals(0L, store.deleteAccessAuditOlderThan(Instant.now().plusSeconds(1)));
            assertEquals(10_000, DashboardStore.REGEX_CANDIDATE_LIMIT);
        }
    }

    @Test
    void deduplicatesEventsAndUsesOnlyVerifyFailInFailureRate() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard;DB_CLOSE_DELAY=-1")) {
            final Instant now = Instant.parse("2026-07-30T01:15:00Z");
            final NormalizedEvent ok = event("a".repeat(64), now, "VERIFY", "OK");
            final NormalizedEvent fail = event("b".repeat(64), now, "VERIFY", "FAIL");
            final NormalizedEvent locked = event("c".repeat(64), now, "VERIFY", "LOCKED");

            final DashboardStore.IngestResult first =
                    store.ingest(List.of(ok, fail, locked), "test", 128, 2, 1);
            final DashboardStore.IngestResult second =
                    store.ingest(List.of(ok, fail, locked), "test", 128, 2, 1);

            assertEquals(3, first.accepted());
            assertEquals(3, second.duplicates());
            final Map<String, Object> summary = store.summary(
                    allScope(),
                    now.minusSeconds(3600), now.plusSeconds(3600));
            assertEquals(1L, summary.get("graphicalMatrixSuccess"));
            assertEquals(1L, summary.get("graphicalMatrixFailure"));
            assertEquals(0.5, (Double) summary.get("graphicalMatrixFailureRate"), 0.00001);
            final Map<String, Object> health = store.ingestHealth().getFirst();
            assertEquals(2L, health.get("parseFailureCount"));
            assertEquals(1L, health.get("clockSkewCount"));
        }
    }

    @Test
    void summaryUsesExactTimeBoundsAndCategoryFilteringPrecedesLimit() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-bounds;DB_CLOSE_DELAY=-1")) {
            final Instant from = Instant.parse("2026-07-30T01:30:00Z");
            store.ingest(List.of(
                    event("d".repeat(64), from.minusSeconds(60), "VERIFY", "FAIL"),
                    event("e".repeat(64), from.plusSeconds(60), "VERIFY", "OK"),
                    event("f".repeat(64), from.plusSeconds(120), "SELF_SERVICE_AUTH", "OK"),
                    event("1".repeat(64), from.plusSeconds(3600), "VERIFY", "OK"),
                    event("2".repeat(64), from.plusSeconds(3 * 3600 - 60), "VERIFY", "OK"),
                    event("3".repeat(64), from.plusSeconds(3 * 3600 + 60), "VERIFY", "FAIL")),
                    "test");

            final Map<String, Object> summary =
                    store.summary(allScope(), from, from.plusSeconds(3 * 3600));
            assertEquals(3L, summary.get("graphicalMatrixSuccess"));
            assertEquals(0L, summary.get("graphicalMatrixFailure"));

            final List<Map<String, Object>> category = store.categoryEvents(
                    allScope(),
                    DashboardEventPolicy.RouteCategory.SELF_SERVICE,
                    from,
                    from.plusSeconds(3 * 3600),
                    null,
                    null,
                    null,
                    null,
                    null,
                    1);
            assertEquals(1, category.size());
            assertEquals("SELF_SERVICE_AUTH", category.getFirst().get("event"));
        }
    }

    @Test
    void summaryIncludesSelectedStartMinuteAndExcludesSelectedEndMinute()
            throws Exception {
        try (DashboardStore store =
                new DashboardStore(
                        "jdbc:h2:mem:dashboard-custom-range;DB_CLOSE_DELAY=-1")) {
            final Instant from = Instant.parse("2026-07-31T01:30:00Z");
            final Instant to = Instant.parse("2026-07-31T02:15:00Z");
            store.ingest(List.of(
                    event("a".repeat(64), from.minusMillis(1), "VERIFY", "OK"),
                    event("b".repeat(64), from, "VERIFY", "OK"),
                    event("c".repeat(64), from.plusSeconds(1), "VERIFY", "OK"),
                    event("d".repeat(64), to.minusMillis(1), "VERIFY", "OK"),
                    event("e".repeat(64), to, "VERIFY", "OK")),
                    "test");

            final Map<String, Object> summary = store.summary(allScope(), from, to);

            assertEquals(3L, summary.get("graphicalMatrixSuccess"));
            assertEquals(from, summary.get("from"));
            assertEquals(to, summary.get("to"));
        }
    }

    @Test
    void eventCursorUsesOccurredTimeAndEventIdWithoutDuplicates() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-cursor;DB_CLOSE_DELAY=-1")) {
            final Instant now = Instant.parse("2026-07-30T01:15:00Z");
            store.ingest(List.of(
                    event("a".repeat(64), now, "VERIFY", "OK"),
                    event("b".repeat(64), now, "VERIFY", "OK"),
                    event("c".repeat(64), now, "VERIFY", "OK")), "test");

            final List<Map<String, Object>> first = store.eventPage(
                    allScope(),
                    now.minusSeconds(1), now.plusSeconds(1),
                    null, null, null, null, null, 2, null);
            final Map<String, Object> last = first.getLast();
            final List<Map<String, Object>> second = store.eventPage(
                    allScope(),
                    now.minusSeconds(1), now.plusSeconds(1),
                    null, null, null, null, null, 2,
                    new DashboardStore.EventCursor(
                            (Instant) last.get("occurredAt"),
                            (String) last.get("eventId")));

            assertEquals(List.of("c".repeat(64), "b".repeat(64)),
                    first.stream().map(row -> row.get("eventId")).toList());
            assertEquals(List.of("a".repeat(64)),
                    second.stream().map(row -> row.get("eventId")).toList());
        }
    }

    @Test
    void filtersEventsByPlainUserIdWithExactCase() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-user;DB_CLOSE_DELAY=-1")) {
            final Instant now = Instant.parse("2026-07-30T01:15:00Z");
            store.ingest(List.of(
                    event("a".repeat(64), now, "VERIFY", "OK", "User_001"),
                    event("b".repeat(64), now, "VERIFY", "FAIL", "user002")), "test");

            final List<Map<String, Object>> matching = store.eventPage(
                    allScope(),
                    now.minusSeconds(1), now.plusSeconds(1),
                    null, null, null, null, "User_001", 100, null);
            final List<Map<String, Object>> wrongCase = store.eventPage(
                    allScope(),
                    now.minusSeconds(1), now.plusSeconds(1),
                    null, null, null, null, "user_001", 100, null);

            assertEquals(1, matching.size());
            assertEquals("User_001", matching.getFirst().get("userRef"));
            assertEquals(0, wrongCase.size());
        }
    }

    @Test
    void summarizesAndFiltersSourceNetworks() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-network;DB_CLOSE_DELAY=-1")) {
            final Instant from = Instant.parse("2026-07-30T01:00:00Z");
            store.ingest(List.of(
                    eventWithNetwork(
                            "1".repeat(64), from.plusSeconds(10),
                            "VERIFY", "OK", "192.0.2.10"),
                    eventWithNetwork(
                            "2".repeat(64), from.plusSeconds(20),
                            "VERIFY", "FAIL", "192.0.2.10"),
                    eventWithNetwork(
                            "3".repeat(64), from.plusSeconds(30),
                            "VERIFY", "LOCKED", "198.51.100.20"),
                    eventWithNetwork(
                            "4".repeat(64), from.plusSeconds(40),
                            "VERIFY", "OK", "192.0.2.10"),
                    eventWithNetwork(
                            "5".repeat(64), from.plusSeconds(50),
                            "VERIFY", "FAIL", "198.51.100.20"),
                    eventWithNetwork(
                            "6".repeat(64), from.plusSeconds(60),
                            "VERIFY", "FAIL", "198.51.100.20"),
                    eventWithNetwork(
                            "7".repeat(64), from.plusSeconds(70),
                            "CHALLENGE_CREATED", "OK", "198.51.100.20")),
                    "test");

            final Map<String, Object> summary =
                    store.summary(allScope(), from, from.plusSeconds(3600), 3, 1);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> top =
                    (List<Map<String, Object>>) summary.get("topSourceNetworks");
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> mismatchTop =
                    (List<Map<String, Object>>)
                            summary.get("topSourceMismatchNetworks");

            assertEquals(1, summary.get("topSourceNetworkLimit"));
            assertEquals(1, top.size());
            assertEquals("192.0.2.10", top.getFirst().get("sourceNetwork"));
            assertEquals(3L, top.getFirst().get("count"));
            assertEquals(1, mismatchTop.size());
            assertEquals("198.51.100.20",
                    mismatchTop.getFirst().get("sourceNetwork"));
            assertEquals(2L, mismatchTop.getFirst().get("count"));

            final List<Map<String, Object>> exact = store.eventPage(
                    allScope(),
                    from, from.plusSeconds(3600),
                    null, null, null, null, null, "198.51.100.20",
                    100, null, null);
            assertEquals(4, exact.size());

            final EventRegexFilter regex = EventRegexFilter.compile(
                    null, null, null, null, null, "^192\\.0\\.2\\.");
            final List<Map<String, Object>> matching = store.eventPage(
                    allScope(),
                    from, from.plusSeconds(3600),
                    null, null, null, null, null, 100, null, regex);
            assertEquals(3, matching.size());
        }
    }

    @Test
    void summarizesTopMismatchUsersAndCurrentLocks() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-operations;DB_CLOSE_DELAY=-1")) {
            final Instant from = Instant.parse("2026-07-30T01:00:00Z");
            final Instant asOf = from.plusSeconds(600);
            store.ingest(List.of(
                    event("1".repeat(64), from.plusSeconds(10), "VERIFY", "FAIL", "user001"),
                    event("2".repeat(64), from.plusSeconds(20), "VERIFY", "FAIL", "user001"),
                    event("3".repeat(64), from.plusSeconds(30), "VERIFY", "FAIL", "user002"),
                    event("4".repeat(64), from.plusSeconds(40), "VERIFY", "FAIL", null),
                    event("5".repeat(64), from.plusSeconds(50), "VERIFY", "LOCKED",
                            "user001", asOf.plusSeconds(900).toEpochMilli()),
                    event("6".repeat(64), from.plusSeconds(60), "VERIFY", "LOCKED",
                            "user002", asOf.plusSeconds(900).toEpochMilli()),
                    event("7".repeat(64), from.plusSeconds(70), "API_UNLOCKED", "OK", "user002"),
                    event("8".repeat(64), from.plusSeconds(80), "VERIFY", "LOCKED",
                            "user003", asOf.minusSeconds(1).toEpochMilli())),
                    "test");

            final Map<String, Object> summary =
                    store.summary(allScope(), from, from.plusSeconds(3600), asOf);
            final Map<String, Object> limited =
                    store.summary(allScope(), from, from.plusSeconds(3600), asOf, 1);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> top =
                    (List<Map<String, Object>>) summary.get("topMismatchUsers");
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> locked =
                    (List<Map<String, Object>>) summary.get("lockedUsers");

            assertEquals(List.of("user001", "user002"),
                    top.stream().map(row -> row.get("userRef")).toList());
            assertEquals(List.of(2L, 1L),
                    top.stream().map(row -> row.get("count")).toList());
            assertEquals(3, summary.get("topMismatchLimit"));
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> limitedTop =
                    (List<Map<String, Object>>) limited.get("topMismatchUsers");
            assertEquals(1, limitedTop.size());
            assertEquals("user001", limitedTop.getFirst().get("userRef"));
            assertEquals(1, limited.get("topMismatchLimit"));
            assertEquals(1L, summary.get("lockedUserCount"));
            assertEquals(List.of("user001"),
                    locked.stream().map(row -> row.get("userRef")).toList());
            assertEquals(asOf.plusSeconds(900), locked.getFirst().get("lockedUntil"));
        }
    }

    @Test
    void filtersEventsWithLinearTimeRegularExpressions() throws Exception {
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:dashboard-regex;DB_CLOSE_DELAY=-1")) {
            final Instant now = Instant.parse("2026-07-30T01:15:00Z");
            store.ingest(List.of(
                    event("a".repeat(64), now, "VERIFY", "FAIL", "loadtest0001"),
                    event("b".repeat(64), now.minusSeconds(1), "VERIFY", "OK", "User_001"),
                    event("c".repeat(64), now.minusSeconds(2), "START", "LOCKED",
                            "loadtest0002", now.plusSeconds(900).toEpochMilli())), "test");

            final EventRegexFilter filter = EventRegexFilter.compile(
                    "^node-", "^VER", "FAIL|LOCKED", null, "^loadtest[0-9]{4}$");
            final List<Map<String, Object>> matching = store.eventPage(
                    allScope(),
                    now.minusSeconds(10), now.plusSeconds(10),
                    null, null, null, null, null, 100, null, filter);

            assertEquals(1, matching.size());
            assertEquals("loadtest0001", matching.getFirst().get("userRef"));
        }
    }

    @Test
    void appliesPrincipalEventScopeBeforeFiltersPagingSummaryAndLockProjection()
            throws Exception {
        try (DashboardStore store = new DashboardStore(
                "jdbc:h2:mem:dashboard-event-scope;DB_CLOSE_DELAY=-1")) {
            final Instant from = Instant.parse("2026-09-15T00:00:00Z");
            final Instant to = from.plusSeconds(3600);
            store.ingest(List.of(
                    event("1".repeat(64), from.plusSeconds(10), "VERIFY", "OK"),
                    event("2".repeat(64), from.plusSeconds(20), "CHANGE_SAVE", "OK"),
                    event("3".repeat(64), from.plusSeconds(30), "API_USER_UPDATED", "OK"),
                    event("4".repeat(64), from.plusSeconds(40), "UNKNOWN_EVENT", "OK"),
                    event("5".repeat(64), from.plusSeconds(50), "APIX_UPDATED", "OK"),
                    event(
                            "6".repeat(64),
                            from.plusSeconds(60),
                            "VERIFY",
                            "LOCKED",
                            "locked-user",
                            to.plusSeconds(900).toEpochMilli())),
                    "test");

            final DashboardEventScope operational = operationalScope();
            final List<Map<String, Object>> firstOperational = store.eventPage(
                    operational,
                    from,
                    to,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    null);
            assertEquals(1, firstOperational.size());
            assertEquals("VERIFY", firstOperational.getFirst().get("event"));
            assertEquals("6".repeat(64), firstOperational.getFirst().get("eventId"));

            final EventRegexFilter allRegex = EventRegexFilter.compile(
                    null, ".*", null, null, null);
            final List<Map<String, Object>> operationalRegex = store.eventPage(
                    operational,
                    from,
                    to,
                    null,
                    null,
                    null,
                    null,
                    null,
                    100,
                    null,
                    allRegex);
            assertEquals(
                    List.of("VERIFY", "CHANGE_SAVE", "VERIFY"),
                    operationalRegex.stream().map(row -> row.get("event")).toList());

            final List<Map<String, Object>> hiddenExact = store.eventPage(
                    operational,
                    from,
                    to,
                    "API_USER_UPDATED",
                    null,
                    null,
                    null,
                    null,
                    100,
                    null);
            assertEquals(0, hiddenExact.size());

            final List<Map<String, Object>> adminEvents = store.categoryEvents(
                    allScope(),
                    DashboardEventPolicy.RouteCategory.ADMIN_API,
                    from,
                    to,
                    null,
                    null,
                    null,
                    null,
                    null,
                    100);
            assertEquals(List.of("API_USER_UPDATED"),
                    adminEvents.stream().map(row -> row.get("event")).toList());

            final List<Map<String, Object>> deniedAdminEvents = store.categoryEvents(
                    operational,
                    DashboardEventPolicy.RouteCategory.ADMIN_API,
                    from,
                    to,
                    null,
                    null,
                    null,
                    null,
                    null,
                    100);
            assertEquals(0, deniedAdminEvents.size());

            final Map<String, Object> operationalSummary =
                    store.summary(operational, from, to, to, 3);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> operationalCounts =
                    (List<Map<String, Object>>) operationalSummary.get("counts");
            assertEquals(
                    List.of("CHANGE_SAVE", "VERIFY", "VERIFY"),
                    operationalCounts.stream().map(row -> row.get("event")).toList());
            assertNull(operationalSummary.get("lockedUserCount"));
            assertEquals(List.of(), operationalSummary.get("lockedUsers"));

            final Map<String, Object> fullSummary =
                    store.summary(allScope(), from, to, to, 3);
            assertEquals(1L, fullSummary.get("lockedUserCount"));
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> lockedUsers =
                    (List<Map<String, Object>>) fullSummary.get("lockedUsers");
            assertEquals("locked-user", lockedUsers.getFirst().get("userRef"));
        }
    }

    @Test
    void migratesVersionOneUserReferenceColumnTo255Characters() throws Exception {
        final String jdbcUrl =
                "jdbc:h2:mem:dashboard-v1;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE dashboard_schema_version (
                      singleton_id SMALLINT PRIMARY KEY,
                      schema_version INTEGER NOT NULL,
                      applied_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO dashboard_schema_version
                    VALUES (1, 1, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE dashboard_event (
                      event_id VARCHAR(64) PRIMARY KEY,
                      schema_version SMALLINT NOT NULL,
                      occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      received_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      node_id VARCHAR(64) NOT NULL,
                      event_type VARCHAR(64) NOT NULL,
                      result VARCHAR(32) NOT NULL,
                      reason VARCHAR(64) NOT NULL,
                      user_ref VARCHAR(64),
                      source_network VARCHAR(64),
                      parse_status VARCHAR(16) NOT NULL,
                      source_generation VARCHAR(64) NOT NULL,
                      source_offset BIGINT NOT NULL,
                      line_digest VARCHAR(64) NOT NULL
                    )
                    """);
        }

        try (DashboardStore ignored = new DashboardStore(jdbcUrl);
                Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT character_maximum_length
                        FROM information_schema.columns
                        WHERE table_name = 'dashboard_event' AND column_name = 'user_ref'
                        """)) {
            resultSet.next();
            assertEquals(255L, resultSet.getLong(1));
        }
    }

    private static NormalizedEvent event(
            final String id,
            final Instant time,
            final String type,
            final String result) {
        return event(id, time, type, result, null);
    }

    private static NormalizedEvent event(
            final String id,
            final Instant time,
            final String type,
            final String result,
            final String user) {
        return new NormalizedEvent(
                1, id, time, time, "node-01", type, result, "test", user, null,
                null, "ok", "1", 0, id);
    }

    private static NormalizedEvent event(
            final String id,
            final Instant time,
            final String type,
            final String result,
            final String user,
            final Long lockedUntil) {
        return new NormalizedEvent(
                1, id, time, time, "node-01", type, result, "test", user, null,
                lockedUntil, "ok", "1", 0, id);
    }

    private static NormalizedEvent eventWithNetwork(
            final String id,
            final Instant time,
            final String type,
            final String result,
            final String sourceNetwork) {
        return new NormalizedEvent(
                1, id, time, time, "node-01", type, result, "test", null, sourceNetwork,
                null, "ok", "1", 0, id);
    }

    private static DashboardEventScope allScope() {
        return DashboardEventPolicy.scopeFor(new DashboardAuthorizer.Principal(
                "auditor",
                DashboardAuthorizer.Role.DASHBOARD_AUDITOR,
                "user:auditor"));
    }

    private static DashboardEventScope operationalScope() {
        return DashboardEventPolicy.scopeFor(new DashboardAuthorizer.Principal(
                "operator",
                DashboardAuthorizer.Role.DASHBOARD_OPERATOR,
                "user:operator"));
    }
}
