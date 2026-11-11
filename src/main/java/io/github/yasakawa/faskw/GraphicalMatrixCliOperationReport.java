/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.util.LinkedHashMap;
import java.util.Map;

/** Safe, statement-derived evidence. Never put credentials, SQL or exception text in a report. */
public record GraphicalMatrixCliOperationReport(String targetUser, long matchedRows, long changedRows,
        Map<Field, String> changes) {
    public enum Field {
        MFA_METHOD, STATUS, FORCE_SEQUENCE_CHANGE, LOCKED_UNTIL, SEED_SET,
        SEQUENCE_CHANGED, INITIAL_SEQUENCE_CHANGED, STATE_VERSION, STORAGE_MODE,
        CREDENTIALS_DELETED, CREDENTIAL_ID_SHA256, CSV_SHA256, CSV_ACTION, CSV_LINE, EXPORTED_ROWS
    }

    public GraphicalMatrixCliOperationReport {
        if (targetUser == null || !targetUser.matches("[A-Za-z0-9._@*-]{1,255}")
                || matchedRows < 0 || changedRows < 0) {
            throw new IllegalArgumentException("INVALID_AUDIT_EVIDENCE");
        }
        changes = Map.copyOf(changes);
        for (var entry : changes.entrySet()) {
            final String value = entry.getValue();
            final boolean valid = switch (entry.getKey()) {
                case MFA_METHOD -> value.matches("GraphicalMatrix|TOTP|WebAuthn");
                case STATUS -> value.matches("ACTIVE|DISABLED|DELETED");
                case FORCE_SEQUENCE_CHANGE, SEED_SET, SEQUENCE_CHANGED, INITIAL_SEQUENCE_CHANGED ->
                    value.matches("true|false|0|1");
                case LOCKED_UNTIL, STATE_VERSION, CREDENTIALS_DELETED, EXPORTED_ROWS, CSV_LINE -> value.matches("[0-9]{1,19}");
                case STORAGE_MODE -> value.matches("plaintext|hash|keyword|aes-gcm|empty");
                case CREDENTIAL_ID_SHA256, CSV_SHA256 -> value.matches("[a-f0-9]{64}");
                case CSV_ACTION -> value.matches("A|M|D");
            };
            if (!valid) { throw new IllegalArgumentException("INVALID_AUDIT_CHANGE"); }
        }
    }

    Map<String, String> fields() {
        final Map<String, String> result = new LinkedHashMap<>();
        result.put("target_user", targetUser);
        result.put("matched_rows", Long.toString(matchedRows));
        result.put("changed_rows", Long.toString(changedRows));
        result.put("changes", changes.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey().name().toLowerCase(java.util.Locale.ROOT) + ":" + e.getValue())
            .collect(java.util.stream.Collectors.joining(",")));
        return result;
    }

    public static GraphicalMatrixCliOperationReport changed(final String user, final long count,
            final Map<Field, String> changes) {
        return new GraphicalMatrixCliOperationReport(user, count, count, changes);
    }
}
