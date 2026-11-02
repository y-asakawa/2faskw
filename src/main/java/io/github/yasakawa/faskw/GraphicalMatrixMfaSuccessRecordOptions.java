/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.io.Serializable;
import java.util.Locale;
import java.util.Properties;

public record GraphicalMatrixMfaSuccessRecordOptions(
        boolean enabled, boolean auditEnabled, int queryTimeoutSeconds) implements Serializable {
    private static final long serialVersionUID = 1L;

    public GraphicalMatrixMfaSuccessRecordOptions {
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 5) {
            throw new IllegalArgumentException("graphicalmatrix.mfa.successRecord.queryTimeoutSeconds must be 1-5");
        }
    }

    static GraphicalMatrixMfaSuccessRecordOptions from(final Properties properties) {
        final String key = "graphicalmatrix.mfa.successRecord.queryTimeoutSeconds";
        final int timeout;
        try {
            timeout = Integer.parseInt(properties.getProperty(key, "1").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return new GraphicalMatrixMfaSuccessRecordOptions(
            bool(properties, "graphicalmatrix.mfa.successRecord.enabled"),
            bool(properties, "graphicalmatrix.mfa.completionAudit.enabled"), timeout);
    }

    private static boolean bool(final Properties properties, final String key) {
        return switch (properties.getProperty(key, "true").trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(key + " must be a boolean");
        };
    }
}
