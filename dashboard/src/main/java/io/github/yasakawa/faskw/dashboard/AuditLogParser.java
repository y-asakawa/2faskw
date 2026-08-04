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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuditLogParser {

    public static final int MAX_LINE_BYTES = 64 * 1024;

    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^ts=(?<ts>[^ ]+) event=(?<event>[^ ]+) user=(?<user>[^ ]+) "
                    + "result=(?<result>[^ ]+) ip=(?<ip>[^ ]+) session=(?<session>[^ ]+) "
                    + "challenge=(?<challenge>[^ ]+) detail=(?<detail>.*)$");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofHours(24);

    private final Clock clock;

    public AuditLogParser() {
        this(Clock.systemUTC());
    }

    AuditLogParser(final Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public RawAuditEvent parse(final String line) throws ParseException {
        Objects.requireNonNull(line, "line");
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new ParseException("line exceeds 64 KiB");
        }
        if (containsControl(line)) {
            throw new ParseException("line contains a control character");
        }

        final Matcher matcher = LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            throw new ParseException("line does not match the audit format");
        }

        final Instant occurredAt;
        try {
            occurredAt = Instant.parse(matcher.group("ts"));
        } catch (DateTimeException e) {
            throw new ParseException("invalid timestamp", e);
        }
        if (occurredAt.isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            throw new ParseException("timestamp is more than 24 hours in the future");
        }

        final String event = matcher.group("event");
        final String result = matcher.group("result");
        if (!SAFE_TOKEN.matcher(event).matches() || !SAFE_TOKEN.matcher(result).matches()) {
            throw new ParseException("event or result contains unsafe characters");
        }
        final String user = decodeIdentifier(matcher.group("user"));
        if (user != null && !PrivacyFilter.isValidUserReference(user)) {
            throw new ParseException("user ID contains unsafe characters");
        }

        return new RawAuditEvent(
                occurredAt,
                event,
                user,
                result,
                decode(matcher.group("ip")),
                decode(matcher.group("session")),
                decode(matcher.group("challenge")),
                decode(matcher.group("detail")));
    }

    static String decode(final String value) throws ParseException {
        if ("-".equals(value)) {
            return null;
        }
        final StringBuilder decoded = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            final char current = value.charAt(i);
            if (escaped) {
                decoded.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> current;
                });
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else {
                decoded.append(current == '_' ? ' ' : current);
            }
        }
        if (escaped) {
            throw new ParseException("value ends with an incomplete escape");
        }
        if (containsControl(decoded)) {
            throw new ParseException("decoded value contains a control character");
        }
        return decoded.toString();
    }

    private static String decodeIdentifier(final String value) throws ParseException {
        if ("-".equals(value)) {
            return null;
        }
        final StringBuilder decoded = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            final char current = value.charAt(i);
            if (escaped) {
                decoded.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> current;
                });
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else {
                decoded.append(current);
            }
        }
        if (escaped) {
            throw new ParseException("value ends with an incomplete escape");
        }
        if (containsControl(decoded)) {
            throw new ParseException("decoded value contains a control character");
        }
        return decoded.toString();
    }

    private static boolean containsControl(final CharSequence value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static final class ParseException extends Exception {
        public ParseException(final String message) {
            super(message);
        }

        public ParseException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
