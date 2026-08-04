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

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.util.Map;

public record EventRegexFilter(
        Pattern node,
        Pattern event,
        Pattern result,
        Pattern reason,
        Pattern user,
        Pattern sourceNetwork) {

    private static final int MAXIMUM_PATTERN_LENGTH = 128;

    public static EventRegexFilter compile(
            final String node,
            final String event,
            final String result,
            final String reason,
            final String user) {
        return compile(node, event, result, reason, user, null);
    }

    public static EventRegexFilter compile(
            final String node,
            final String event,
            final String result,
            final String reason,
            final String user,
            final String sourceNetwork) {
        final EventRegexFilter filter = new EventRegexFilter(
                compile("node", node),
                compile("event", event),
                compile("result", result),
                compile("reason", reason),
                compile("user", user),
                compile("ip", sourceNetwork));
        if (filter.node == null && filter.event == null && filter.result == null
                && filter.reason == null && filter.user == null
                && filter.sourceNetwork == null) {
            throw new IllegalArgumentException(
                    "regex mode requires at least one filter pattern");
        }
        return filter;
    }

    public boolean matches(final Map<String, Object> row) {
        return matches(node, row.get("nodeId"))
                && matches(event, row.get("event"))
                && matches(result, row.get("result"))
                && matches(reason, row.get("reason"))
                && matches(user, row.get("userRef"))
                && matches(sourceNetwork, row.get("sourceNetwork"));
    }

    private static Pattern compile(final String name, final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAXIMUM_PATTERN_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " regex must contain at most "
                            + MAXIMUM_PATTERN_LENGTH + " printable characters");
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(name + " regex is invalid", e);
        }
    }

    private static boolean matches(final Pattern pattern, final Object value) {
        if (pattern == null) {
            return true;
        }
        return value != null && pattern.matcher(value.toString()).find();
    }
}
