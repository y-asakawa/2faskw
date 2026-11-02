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

public final class GraphicalMatrixVerifyResult {
    private final boolean success;
    private final String event;
    private final String auditResult;
    private final String auditDetail;
    private final long lockedUntil;

    private GraphicalMatrixVerifyResult(final boolean success, final String event,
            final String auditResult, final String auditDetail, final long lockedUntil) {
        this(success, event, auditResult, auditDetail, lockedUntil, null, null);
    }

    private GraphicalMatrixVerifyResult(final boolean success, final String event,
            final String auditResult, final String auditDetail, final long lockedUntil,
            final Long beforeVersion, final Long afterVersion) {
        this.beforeVersion = beforeVersion;
        this.afterVersion = afterVersion;
        this.success = success;
        this.event = event;
        this.auditResult = auditResult;
        this.auditDetail = auditDetail;
        this.lockedUntil = lockedUntil;
    }

    public static GraphicalMatrixVerifyResult success() {
        return new GraphicalMatrixVerifyResult(true, null, "OK", "matched", 0L);
    }

    public static GraphicalMatrixVerifyResult success(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(true, null, "OK", auditDetail, 0L);
    }

    public static GraphicalMatrixVerifyResult failed(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(
            false, "GraphicalMatrixFailed", "FAIL", auditDetail, 0L);
    }

    public static GraphicalMatrixVerifyResult locked(final String auditDetail) {
        return locked(auditDetail, 0L);
    }

    public static GraphicalMatrixVerifyResult locked(final String auditDetail,
            final long lockedUntil) {
        return new GraphicalMatrixVerifyResult(
            false, "GraphicalMatrixLocked", "LOCKED", auditDetail, lockedUntil);
    }

    public static GraphicalMatrixVerifyResult enrollRequired(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(
            false, "GraphicalMatrixEnrollRequired", "ENROLL_REQUIRED", auditDetail, 0L);
    }

    public static GraphicalMatrixVerifyResult dbError(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(
            false, "GraphicalMatrixFailed", "DB_ERROR", auditDetail, 0L);
    }

    private final Long beforeVersion;
    private final Long afterVersion;

    GraphicalMatrixVerifyResult withStateTransition(final long before, final long after) {
        if (before < 0 || after < before || (after != before && after != Math.addExact(before, 1L))) {
            throw new IllegalArgumentException("Invalid verification generation transition");
        }
        return new GraphicalMatrixVerifyResult(success, event, auditResult, auditDetail, lockedUntil,
            Long.valueOf(before), Long.valueOf(after));
    }

    Long beforeVersion() { return beforeVersion; }
    Long afterVersion() { return afterVersion; }

    public boolean isSuccess() {
        return success;
    }

    public String getEvent() {
        return event;
    }

    public String getAuditResult() {
        return auditResult;
    }

    public String getAuditDetail() {
        return auditDetail;
    }

    public long getLockedUntil() {
        return lockedUntil;
    }
}
