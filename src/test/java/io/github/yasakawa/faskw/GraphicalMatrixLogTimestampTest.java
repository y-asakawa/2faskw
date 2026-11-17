/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixLogTimestampTest {
    @TempDir Path home;

    @Test void utcAndTokyoPreserveTheInstantAndNanoseconds() {
        final Instant instant = Instant.parse("2026-11-05T01:43:30.518123456Z");
        assertEquals("2026-11-05T01:43:30.518123456Z", GraphicalMatrixLogTimestamp.format(instant, ZoneId.of("UTC")));
        final String tokyo = GraphicalMatrixLogTimestamp.format(instant, ZoneId.of("Asia/Tokyo"));
        assertEquals("2026-11-05T10:43:30.518123456+09:00", tokyo);
        assertEquals(instant, Instant.parse(tokyo));
    }

    @Test void handlesFractionalOffsetsAndLocalDateRollover() {
        final Instant instant = Instant.parse("2026-01-15T20:30:00.004Z");
        assertEquals("2026-01-16T05:30:00.004+09:00", GraphicalMatrixLogTimestamp.format(instant, ZoneId.of("Asia/Tokyo")));
        assertEquals("2026-01-16T02:15:00.004+05:45", GraphicalMatrixLogTimestamp.format(instant, ZoneId.of("Asia/Kathmandu")));
        assertEquals("2026-01-15T17:00:00.004-03:30", GraphicalMatrixLogTimestamp.format(instant, ZoneId.of("America/St_Johns")));
    }

    @Test void daylightSavingOverlapIsUnambiguousAndUsesTheEventInstant() {
        final ZoneId zone = ZoneId.of("America/New_York");
        final Instant first = Instant.parse("2026-11-01T05:30:00Z");
        final Instant second = Instant.parse("2026-11-01T06:30:00Z");
        assertEquals("2026-11-01T01:30:00-04:00", GraphicalMatrixLogTimestamp.format(first, zone));
        assertEquals("2026-11-01T01:30:00-05:00", GraphicalMatrixLogTimestamp.format(second, zone));
        assertEquals(first, Instant.parse(GraphicalMatrixLogTimestamp.format(first, zone)));
        assertEquals(second, Instant.parse(GraphicalMatrixLogTimestamp.format(second, zone)));
    }

    @Test void defaultOutputUsesTheJvmSystemZone() {
        final Instant instant = Instant.parse("2026-11-05T01:43:30.518Z");
        assertEquals(GraphicalMatrixLogTimestamp.format(instant, ZoneId.systemDefault()), GraphicalMatrixLogTimestamp.format(instant));
        final OffsetDateTime now = OffsetDateTime.parse(GraphicalMatrixLogTimestamp.now());
        assertEquals(ZoneId.systemDefault().getRules().getOffset(now.toInstant()), now.getOffset());
    }

    @Test void authenticationAndAdminAuditUseTheSameZoneWithoutChangingCompletionMillis() throws Exception {
        final Instant instant = Instant.parse("2026-11-05T01:43:30.518Z");
        Files.createDirectories(home.resolve("logs"));
        assertTrue(new GraphicalMatrixAuditLogger(home.toString()).logCompletion("user001", "test", null, instant.toEpochMilli()));
        final String line = Files.readString(home.resolve("logs/graphicalmatrix-audit.log"));
        assertTrue(line.startsWith("ts=" + GraphicalMatrixLogTimestamp.format(instant) + " event=MFA_COMPLETE "));
        final String admin = new String(GraphicalMatrixAdminAuditWriter.encode(Map.of("event", "ADMIN_CLI_START")), StandardCharsets.UTF_8);
        final String adminTimestamp = admin.substring(admin.indexOf("ts=") + 3, admin.indexOf(" event="));
        final OffsetDateTime parsed = OffsetDateTime.parse(adminTimestamp);
        assertEquals(ZoneId.systemDefault().getRules().getOffset(parsed.toInstant()), parsed.getOffset());
    }
}
