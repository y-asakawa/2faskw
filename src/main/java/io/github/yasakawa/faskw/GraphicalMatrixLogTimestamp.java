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
package io.github.yasakawa.faskw;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Local log presentation only; retain the instant and its precision for machine readers. */
public final class GraphicalMatrixLogTimestamp {
    private GraphicalMatrixLogTimestamp() { }

    public static String now() {
        return format(Instant.now());
    }

    public static String format(final Instant timestamp) {
        return format(timestamp, ZoneId.systemDefault());
    }

    static String format(final Instant timestamp, final ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp.atZone(zone));
    }
}
