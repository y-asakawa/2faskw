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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuditLogParserTest {

    private final AuditLogParser parser = new AuditLogParser(
            Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void parsesKnownFixedOrderFormat() throws Exception {
        final RawAuditEvent event = parser.parse(
                "ts=2026-07-29T23:59:00Z event=VERIFY user=user001 result=OK "
                        + "ip=192.0.2.10 session=node01 challenge=abc detail=matched");

        assertEquals("VERIFY", event.event());
        assertEquals("user001", event.user());
        assertEquals("192.0.2.10", event.ip());
        assertEquals("matched", event.detail());
    }

    @Test
    void decodesEscapesAndMissingValues() throws Exception {
        final RawAuditEvent event = parser.parse(
                "ts=2026-07-29T23:59:00Z event=START user=- result=FAIL "
                        + "ip=- session=- challenge=- detail=missing_enrollment");

        assertNull(event.user());
        assertNull(event.ip());
        assertEquals("missing enrollment", event.detail());
    }

    @Test
    void mixedUtcAndOffsetLogsPreserveTheSameInstantAndPrecision() throws Exception {
        final Instant expected = Instant.parse("2026-07-29T23:59:00.123456789Z");
        for (String timestamp : new String[]{"2026-07-29T23:59:00.123456789Z",
                "2026-07-30T08:59:00.123456789+09:00", "2026-07-30T05:44:00.123456789+05:45",
                "2026-07-29T19:59:00.123456789-04:00"}) {
            assertEquals(expected, parser.parse("ts=" + timestamp
                + " event=VERIFY user=user001 result=OK ip=- session=- challenge=- detail=matched").occurredAt());
        }
        assertThrows(AuditLogParser.ParseException.class, () -> parser.parse(
            "ts=2026-07-29T23:59:00 event=VERIFY user=user001 result=OK ip=- session=- challenge=- detail=matched"));
        assertThrows(AuditLogParser.ParseException.class, () -> parser.parse(
            "ts=2026-07-31T09:00:01+09:00 event=VERIFY user=user001 result=OK ip=- session=- challenge=- detail=matched"));
    }

    @Test
    void preservesUnderscoresInUserIds() throws Exception {
        final RawAuditEvent event = parser.parse(
                "ts=2026-07-29T23:59:00Z event=VERIFY user=user_001 result=OK "
                        + "ip=192.0.2.10 session=node01 challenge=abc detail=matched");

        assertEquals("user_001", event.user());
    }

    @Test
    void rejectsUnsafeUserIds() {
        assertThrows(AuditLogParser.ParseException.class, () -> parser.parse(
                "ts=2026-07-29T23:59:00Z event=VERIFY user=user:001 result=OK "
                        + "ip=192.0.2.10 session=node01 challenge=abc detail=matched"));
    }

    @Test
    void rejectsWrongOrderAndFutureTimestamp() {
        assertThrows(AuditLogParser.ParseException.class, () -> parser.parse(
                "event=VERIFY ts=2026-07-29T23:59:00Z user=u result=OK "
                        + "ip=- session=- challenge=- detail=matched"));
        assertThrows(AuditLogParser.ParseException.class, () -> parser.parse(
                "ts=2026-08-01T00:00:01Z event=VERIFY user=u result=OK "
                        + "ip=- session=- challenge=- detail=matched"));
    }

    @Test
    void rejectsOversizedLine() {
        assertThrows(AuditLogParser.ParseException.class,
                () -> parser.parse("x".repeat(AuditLogParser.MAX_LINE_BYTES + 1)));
    }
}
