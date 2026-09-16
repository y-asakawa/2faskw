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

/** Result of a transaction-bound TOTP enrollment operation. */
public final class GraphicalMatrixTotpEnrollmentResult {
    public enum Status {
        STARTED, ACTIVATED, RETRY, CANCELLED, STALE, EXPIRED, LOCKED, UNAVAILABLE
    }

    private final Status status;
    private final GraphicalMatrixTotpEnrollmentBinding binding;
    private final String seed;
    private final long lockedUntil;
    private final String detail;

    private GraphicalMatrixTotpEnrollmentResult(final Status status,
            final GraphicalMatrixTotpEnrollmentBinding binding, final String seed,
            final long lockedUntil, final String detail) {
        this.status = status;
        this.binding = binding;
        this.seed = seed;
        this.lockedUntil = lockedUntil;
        this.detail = detail == null ? "" : detail;
    }

    public static GraphicalMatrixTotpEnrollmentResult started(
            final GraphicalMatrixTotpEnrollmentBinding binding, final String seed) {
        return new GraphicalMatrixTotpEnrollmentResult(Status.STARTED, binding, seed, 0L,
            "totp_registration_started");
    }

    public static GraphicalMatrixTotpEnrollmentResult activated() {
        return new GraphicalMatrixTotpEnrollmentResult(Status.ACTIVATED, null, null, 0L,
            "totp_registered");
    }

    public static GraphicalMatrixTotpEnrollmentResult retry(
            final GraphicalMatrixTotpEnrollmentBinding binding, final String seed) {
        return new GraphicalMatrixTotpEnrollmentResult(Status.RETRY, binding, seed, 0L,
            "totp_registration_code_mismatch");
    }

    public static GraphicalMatrixTotpEnrollmentResult cancelled() {
        return new GraphicalMatrixTotpEnrollmentResult(Status.CANCELLED, null, null, 0L,
            "totp_registration_cancelled");
    }

    public static GraphicalMatrixTotpEnrollmentResult stale(final String detail) {
        return new GraphicalMatrixTotpEnrollmentResult(Status.STALE, null, null, 0L, detail);
    }

    public static GraphicalMatrixTotpEnrollmentResult expired() {
        return new GraphicalMatrixTotpEnrollmentResult(Status.EXPIRED, null, null, 0L,
            "totp_registration_expired");
    }

    public static GraphicalMatrixTotpEnrollmentResult locked(final long lockedUntil) {
        return new GraphicalMatrixTotpEnrollmentResult(Status.LOCKED, null, null, lockedUntil,
            "locked_until=" + lockedUntil);
    }

    public static GraphicalMatrixTotpEnrollmentResult unavailable(final String detail) {
        return new GraphicalMatrixTotpEnrollmentResult(Status.UNAVAILABLE, null, null, 0L, detail);
    }

    public Status getStatus() {
        return status;
    }

    public GraphicalMatrixTotpEnrollmentBinding getBinding() {
        return binding;
    }

    public String getSeed() {
        return seed;
    }

    public long getLockedUntil() {
        return lockedUntil;
    }

    public String getAuditResult() {
        return switch (status) {
            case STARTED, ACTIVATED, CANCELLED -> "OK";
            case RETRY -> "FAIL";
            case LOCKED -> "LOCKED";
            case STALE, EXPIRED -> "ENROLL_REQUIRED";
            case UNAVAILABLE -> "DB_ERROR";
        };
    }

    public String getAuditDetail() {
        return detail;
    }
}
