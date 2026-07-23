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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public final class GraphicalMatrixSupport {
    private static final SecureRandom RNG = new SecureRandom();
    private GraphicalMatrixSupport() {
    }

    public static String token() {
        final byte[] data = new byte[24];
        RNG.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static List<String> shuffledGraphicalIds(final GraphicalMatrixConfig config) {
        final List<String> displayOrder = new ArrayList<>(config.getGraphicalIds());
        Collections.shuffle(displayOrder, RNG);
        return displayOrder;
    }

    public static List<String> csv(final String value) {
        final List<String> out = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return out;
        }
        for (final String raw : value.split(",")) {
            final String item = raw.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    public static String html(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
