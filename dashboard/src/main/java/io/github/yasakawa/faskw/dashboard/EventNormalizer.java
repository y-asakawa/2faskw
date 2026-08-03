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

import java.time.Clock;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EventNormalizer {

    private static final Pattern LOCKED_UNTIL =
            Pattern.compile("(?:^|,)locked[ _]until=([0-9]{1,19})(?:,|$)");

    private final PrivacyFilter privacyFilter;
    private final ReasonMapper reasonMapper;
    private final Clock clock;

    public EventNormalizer(final PrivacyFilter privacyFilter, final ReasonMapper reasonMapper) {
        this(privacyFilter, reasonMapper, Clock.systemUTC());
    }

    EventNormalizer(
            final PrivacyFilter privacyFilter,
            final ReasonMapper reasonMapper,
            final Clock clock) {
        this.privacyFilter = Objects.requireNonNull(privacyFilter);
        this.reasonMapper = Objects.requireNonNull(reasonMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public NormalizedEvent normalize(
            final RawAuditEvent raw,
            final String nodeId,
            final String generation,
            final long offset,
            final String originalLine) {
        final String lineDigest = CryptoSupport.sha256(originalLine);
        final String eventId = CryptoSupport.sha256(
                nodeId + '\n' + generation + '\n' + offset + '\n' + lineDigest);
        return new NormalizedEvent(
                1,
                eventId,
                raw.occurredAt(),
                clock.instant(),
                nodeId,
                raw.event(),
                raw.result(),
                reasonMapper.map(raw.detail()),
                privacyFilter.userRef(raw.user()),
                privacyFilter.sourceNetwork(raw.ip()),
                lockedUntil(raw),
                "ok",
                generation,
                offset,
                lineDigest);
    }

    private static Long lockedUntil(final RawAuditEvent raw) {
        if (!"LOCKED".equals(raw.result()) || raw.detail() == null) {
            return null;
        }
        final Matcher matcher = LOCKED_UNTIL.matcher(raw.detail());
        if (!matcher.find()) {
            return null;
        }
        try {
            final long value = Long.parseLong(matcher.group(1));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
