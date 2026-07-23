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

public final class GraphicalMatrixEnrollment {
    private final String sequence;
    private final String status;
    private final int failedCount;
    private final long lockedUntil;
    private final boolean forceSequenceChange;
    private final long stateVersion;

    public GraphicalMatrixEnrollment(final String sequence, final String status,
            final int failedCount, final long lockedUntil) {
        this(sequence, status, failedCount, lockedUntil, false, 0L);
    }

    public GraphicalMatrixEnrollment(final String sequence, final String status,
            final int failedCount, final long lockedUntil, final boolean forceSequenceChange) {
        this(sequence, status, failedCount, lockedUntil, forceSequenceChange, 0L);
    }

    public GraphicalMatrixEnrollment(final String sequence, final String status,
            final int failedCount, final long lockedUntil, final boolean forceSequenceChange,
            final long stateVersion) {
        this.sequence = sequence;
        this.status = status;
        this.failedCount = failedCount;
        this.lockedUntil = lockedUntil;
        this.forceSequenceChange = forceSequenceChange;
        this.stateVersion = stateVersion;
    }

    public String getSequence() {
        return sequence;
    }

    public String getStatus() {
        return status;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public long getLockedUntil() {
        return lockedUntil;
    }

    public boolean isForceSequenceChange() {
        return forceSequenceChange;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
