/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MfaCompletionTest {
    @TempDir Path home;
    private static final Instant TIME = Instant.ofEpochMilli(1700000000123L);
    private static final Clock CLOCK = Clock.fixed(TIME.plusSeconds(1), ZoneOffset.UTC);

    private String fixture() throws Exception {
        return Files.readString(Path.of("../test-fixtures/mfa-completion-v1.txt")).strip();
    }
    private NormalizedEvent normalized() throws Exception {
        final String line = fixture();
        return new EventNormalizer(new PrivacyFilter(PrivacyFilter.UserMode.PLAIN, PrivacyFilter.IpMode.DROP, null),
            new ReasonMapper(null), CLOCK).normalize(new AuditLogParser(CLOCK).parse(line), "node-01", "g1", 0L, line);
    }
    private String detail() throws Exception { return new AuditLogParser(CLOCK).parse(fixture()).detail(); }
    private String form() throws Exception {
        return new String(Base64.getDecoder().decode(detail().substring(5)), StandardCharsets.UTF_8);
    }
    private static String encode(final String form) {
        return "mfa1:" + Base64.getEncoder().encodeToString(form.getBytes(StandardCharsets.UTF_8));
    }
    private static DashboardEventScope scope() {
        return DashboardEventScope.fromPrincipal(new DashboardAuthorizer.Principal("viewer",
            DashboardAuthorizer.Role.DASHBOARD_VIEWER, "viewer"));
    }

    @Test void decodesProductionFixtureAndDiscardsRequesterBeforeForwarding() throws Exception {
        final NormalizedEvent event = normalized();
        assertEquals("TOTP", event.mfaCompletion().method());
        assertEquals("sso", event.mfaCompletion().scope());
        assertEquals(7L, event.mfaCompletion().stateVersion());
        assertEquals(TIME.toEpochMilli(), event.mfaCompletion().completedAt());
        final String json = JsonSupport.MAPPER.writeValueAsString(event);
        assertFalse(json.contains("sp.example.org"));
        assertFalse(json.contains("a_b"));
        assertEquals(event, JsonSupport.MAPPER.readValue(json, NormalizedEvent.class));
        final com.fasterxml.jackson.databind.node.ObjectNode oldJson = JsonSupport.MAPPER.valueToTree(event);
        oldJson.remove("mfaCompletion");
        assertNull(JsonSupport.MAPPER.treeToValue(oldJson, NormalizedEvent.class).mfaCompletion());
    }

    @Test void rejectsMalformedUnknownDuplicateAndInconsistentFields() throws Exception {
        final String form = form();
        for (final String invalid : List.of(form + "&method=TOTP", form + "&unknown=x",
                form.replace("method=TOTP", "method=Other"), form.replace("flow=authn%2FTOTP", "flow=authn%2FWebAuthn"),
                form.replace("store=UPDATED", "store=NOT_ATTEMPTED"), form.replace("stateVersion=7", "stateVersion=9223372036854775808"),
                form.replace("scope=sso", "scope=%FF"), form.replace("scope=sso", "scope=sso%0A"))) {
            assertThrows(IllegalArgumentException.class, () -> MfaCompletionInfo.decode(encode(invalid)), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> MfaCompletionInfo.decode("mfa1:___"));
        assertThrows(IllegalArgumentException.class, () -> MfaCompletionInfo.decode("mfa1:" + "A".repeat(16384)));
        assertThrows(AuditLogParser.ParseException.class, () -> new AuditLogParser(CLOCK).parse(
            fixture().replace("2023-11-14T22:13:20.123Z", "2023-11-14T22:13:20.124Z")));
        assertThrows(AuditLogParser.ParseException.class, () -> new AuditLogParser(CLOCK).parse(
            fixture().replace("2023-11-14T22:13:20.123Z", "2023-11-14T22:13:20.123001Z")));
        assertThrows(AuditLogParser.ParseException.class, () -> new AuditLogParser(CLOCK).parse(
            fixture().replace("2023-11-14T22:13:20.123Z", Instant.MIN.toString())));
    }

    @Test void offsetTimestampKeepsMfaCompletionEpochAndRejectsAChangedInstant() throws Exception {
        final String fixture = fixture();
        final int timestampEnd = fixture.indexOf(' ');
        final String line = "ts=2023-11-15T07:13:20.123+09:00" + fixture.substring(timestampEnd);
        final RawAuditEvent parsed = new AuditLogParser(CLOCK).parse(line);
        assertEquals(TIME, parsed.occurredAt());
        assertEquals(TIME.toEpochMilli(), MfaCompletionInfo.decode(parsed.detail()).completedAt());
        assertThrows(AuditLogParser.ParseException.class, () -> new AuditLogParser(CLOCK).parse(
            line.replace("07:13:20.123+09:00", "07:13:20.124+09:00")));
    }

    @Test void outerEscapeIsDecodedOnlyOnceBeforeBasicBase64AndForm() throws Exception {
        final String encoded = encode(form().replace("scope=sso", "scope=other"));
        final String line = fixture().substring(0, fixture().indexOf("detail=")) + "detail=" + encoded.replace("=", "\\=");
        assertEquals("other", MfaCompletionInfo.decode(new AuditLogParser(CLOCK).parse(line).detail()).scope());
        assertTrue(form().contains("a_b"));
        assertTrue(form().contains("%2B"));
    }

    @Test void storesNewMetricsSeparatelyFromVerifyAndDeduplicatesReplay() throws Exception {
        try (DashboardStore store = new DashboardStore("jdbc:h2:mem:mfa-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")) {
            final NormalizedEvent completion = normalized();
            final NormalizedEvent verify = new NormalizedEvent(1, "a".repeat(64), TIME, TIME, "node-01", "VERIFY",
                "OK", "unknown", "sample01", null, null, "ok", "g1", 1L, "b".repeat(64));
            assertEquals(2, store.ingest(List.of(completion, verify), "test").accepted());
            assertEquals(2, store.ingest(List.of(completion, verify), "test").duplicates());
            final var summary = store.summary(scope(), TIME.minusSeconds(1), TIME.plusSeconds(1));
            assertEquals(1L, summary.get("graphicalMatrixSuccess"));
            assertEquals(1L, summary.get("mfaCompletionSuccess"));
            final var rows = store.categoryEvents(scope(), DashboardEventPolicy.RouteCategory.AUTHENTICATION,
                TIME.minusSeconds(1), TIME.plusSeconds(1), null, null, null, null, null, 100);
            assertEquals(2, rows.size());
            assertEquals(completion.mfaCompletion(), rows.stream().filter(row -> "MFA_COMPLETE".equals(row.get("event")))
                .findFirst().orElseThrow().get("mfaCompletion"));
        }
    }

    @Test void migratesSchemaFiveAndRetainsOldAgentEventsWithoutInventingMethod() throws Exception {
        final String url = "jdbc:h2:file:" + home.resolve("migrate-mfa");
        final NormalizedEvent old = new NormalizedEvent(1, "c".repeat(64), TIME, TIME, "node-01", "MFA_COMPLETE",
            "OK", "unknown", "sample01", null, null, "ok", "g1", 1L, "d".repeat(64));
        try (DashboardStore store = new DashboardStore(url)) { store.ingest(List.of(old), "old-agent"); }
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            for (final String col : List.of("mfa_method", "mfa_flow", "mfa_scope", "mfa_store", "mfa_completed_at", "mfa_completion_id", "mfa_state_version")) {
                sql.execute("ALTER TABLE dashboard_event DROP COLUMN " + col);
            }
            sql.execute("UPDATE dashboard_schema_version SET schema_version=5");
        }
        try (DashboardStore store = new DashboardStore(url)) {
            assertEquals(0L, store.summary(scope(), TIME.minusSeconds(1), TIME.plusSeconds(1)).get("mfaCompletionSuccess"));
            assertEquals(1, store.categoryEvents(scope(), DashboardEventPolicy.RouteCategory.AUTHENTICATION,
                TIME.minusSeconds(1), TIME.plusSeconds(1), null, null, null, null, null, 100).size());
            store.ingest(List.of(normalized()), "new-agent");
            assertEquals(1L, store.summary(scope(), TIME.minusSeconds(1), TIME.plusSeconds(1)).get("mfaCompletionSuccess"));
        }
    }
}
