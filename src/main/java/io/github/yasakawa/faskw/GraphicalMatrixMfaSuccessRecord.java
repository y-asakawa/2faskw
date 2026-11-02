/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.time.Clock;

/** Separates mandatory enrollment validation from best-effort completion metadata. */
final class GraphicalMatrixMfaSuccessRecord {
    private GraphicalMatrixMfaSuccessRecord() { }

    enum StateVerdict { VALID, REJECTED }
    enum StoreStatus { UPDATED, UNCHANGED, DISABLED, SKIPPED_CONTENDED, FAILED, NOT_ATTEMPTED }

    record Expectation(String user, String method, String flow, long stateVersion) {
        static Expectation from(final GraphicalMatrixMfaGuardContext guard) {
            return new Expectation(guard.user(), guard.expectedMethod(), guard.expectedFlow(), guard.stateVersion());
        }

        boolean matches(final GraphicalMatrixMfaSettings current) {
            if (current == null || !current.isActive() || !current.hasValidStatus()
                    || current.getStateVersion() != stateVersion
                    || !method.equals(GraphicalMatrixMfaCompletionStrategy.normalizeMethod(current.getMethod()))) {
                return false;
            }
            return switch (method) {
                case "TOTP" -> current.isTotpActive();
                case "GRAPHICALMATRIX" -> current.isSequenceSet();
                case "WEBAUTHN" -> true;
                default -> false;
            };
        }
    }

    record Snapshot(GraphicalMatrixMfaSettings settings, String location, String rawStatus,
            String rawVersion, String lastSuccess, String updatedAt, boolean versionPresent) { }

    record Result(StateVerdict stateVerdict, StoreStatus recordStatus, long completedAtMillis,
            String failureCode, boolean futureTimestamp) {
        boolean valid() { return stateVerdict == StateVerdict.VALID; }
        static Result rejected() {
            return new Result(StateVerdict.REJECTED, StoreStatus.NOT_ATTEMPTED, 0, null, false);
        }
        Result failed(final String code) {
            return valid() ? new Result(stateVerdict, StoreStatus.FAILED, completedAtMillis, code, futureTimestamp) : this;
        }
    }

    interface Store {
        Snapshot read(boolean includeTimestamps) throws Exception;
        boolean update(Snapshot snapshot, long completedAt, int timeoutSeconds) throws Exception;
    }

    static Result checkAndRecord(final Expectation expected, final GraphicalMatrixMfaSuccessRecordOptions options,
            final Clock clock, final Store store) throws Exception {
        // Only this first lookup is mandatory; failures here must deny authentication.
        final Snapshot current = store.read(options.enabled());
        if (current == null || !expected.matches(current.settings())) {
            return Result.rejected();
        }
        final long completedAt = clock.millis();
        if (!options.enabled()) {
            return new Result(StateVerdict.VALID, StoreStatus.DISABLED, completedAt, null, false);
        }
        boolean future = false;
        try {
            if (!current.versionPresent()) {
                throw new IllegalStateException("missing_state_version");
            }
            if (timestamp(current.rawVersion()) != expected.stateVersion()) {
                throw new IllegalStateException("invalid_state_version");
            }
            final long last = timestamp(current.lastSuccess());
            timestamp(current.updatedAt());
            future = last > completedAt;
            if (last >= completedAt) {
                return new Result(StateVerdict.VALID, StoreStatus.UNCHANGED, completedAt, null, future);
            }
            if (store.update(current, completedAt, options.queryTimeoutSeconds())) {
                return new Result(StateVerdict.VALID, StoreStatus.UPDATED, completedAt, null, false);
            }
            // A single re-read distinguishes a newer timestamp from an administrative reset.
            final Snapshot after = store.read(true);
            if (after == null || !expected.matches(after.settings())) {
                return Result.rejected();
            }
            final long afterTime = timestamp(after.lastSuccess());
            return new Result(StateVerdict.VALID,
                afterTime >= completedAt ? StoreStatus.UNCHANGED : StoreStatus.SKIPPED_CONTENDED,
                completedAt, null, afterTime > completedAt);
        } catch (Exception ex) {
            return new Result(StateVerdict.VALID, StoreStatus.FAILED, completedAt, "metadata_store_failed", future);
        }
    }

    static long timestamp(final String value) {
        if (value == null) { return 0L; } // An absent LDAP timestamp is not the string "0" for assertions.
        if (!value.matches("[0-9]{1,19}")) { throw new IllegalArgumentException("invalid_timestamp"); }
        final long parsed = Long.parseLong(value);
        if (parsed < 0) { throw new IllegalArgumentException("invalid_timestamp"); }
        return parsed;
    }
}
