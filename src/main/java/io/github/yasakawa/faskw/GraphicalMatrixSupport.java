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
