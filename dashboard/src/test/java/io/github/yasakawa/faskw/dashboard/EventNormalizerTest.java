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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EventNormalizerTest {

    @Test
    void retainsLockExpiryOnlyForLockedEvents() throws Exception {
        final Instant occurredAt = Instant.parse("2026-07-30T01:15:00Z");
        final EventNormalizer normalizer = new EventNormalizer(
                new PrivacyFilter(
                        PrivacyFilter.UserMode.PLAIN, PrivacyFilter.IpMode.DROP, null),
                new ReasonMapper(null),
                Clock.fixed(occurredAt.plusSeconds(1), ZoneOffset.UTC));
        final RawAuditEvent locked = new RawAuditEvent(
                occurredAt, "VERIFY", "user001", "LOCKED", null, null, null,
                "failed_count=5,lock_seconds=900,locked_until=1785375000000");
        final RawAuditEvent failed = new RawAuditEvent(
                occurredAt, "VERIFY", "user001", "FAIL", null, null, null,
                "failed_count=4,locked_until=0");

        assertEquals(1785375000000L,
                normalizer.normalize(locked, "node-01", "1", 0, "locked").lockedUntil());
        assertNull(normalizer.normalize(failed, "node-01", "1", 1, "failed").lockedUntil());
    }

    @Test
    void extractsLockExpiryAfterParsingTheProductionAuditFormat() throws Exception {
        final Instant occurredAt = Instant.parse("2026-07-30T01:15:00Z");
        final EventNormalizer normalizer = new EventNormalizer(
                new PrivacyFilter(
                        PrivacyFilter.UserMode.PLAIN, PrivacyFilter.IpMode.DROP, null),
                new ReasonMapper(null),
                Clock.fixed(occurredAt.plusSeconds(1), ZoneOffset.UTC));
        final RawAuditEvent locked = new AuditLogParser(
                Clock.fixed(occurredAt.plusSeconds(1), ZoneOffset.UTC)).parse(
                        "ts=2026-07-30T01:15:00Z event=VERIFY user=user001 result=LOCKED "
                                + "ip=192.0.2.1 session=s1 challenge=c1 "
                                + "detail=failed_count\\=5,lock_seconds\\=900,"
                                + "locked_until\\=1785375000000");

        assertEquals(1785375000000L,
                normalizer.normalize(locked, "node-01", "1", 0, "locked").lockedUntil());
    }
}
