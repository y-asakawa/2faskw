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

public final class GraphicalMatrixMfaSettings {
    private final String status;
    private final String method;
    private final String totpStatus;
    private final boolean totpSeedSet;
    private final boolean sequenceSet;
    private final long stateVersion;

    public GraphicalMatrixMfaSettings(final String status, final String method,
            final String totpStatus, final boolean totpSeedSet,
            final boolean sequenceSet, final long stateVersion) {
        this.status = status;
        this.method = method;
        this.totpStatus = totpStatus;
        this.totpSeedSet = totpSeedSet;
        this.sequenceSet = sequenceSet;
        this.stateVersion = stateVersion;
    }

    public String getStatus() {
        return status;
    }

    public String getMethod() {
        return method;
    }

    public String getTotpStatus() {
        return totpStatus;
    }

    public boolean isTotpSeedSet() {
        return totpSeedSet;
    }

    public boolean isSequenceSet() {
        return sequenceSet;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }

    public boolean hasValidStatus() {
        return isActive() || isDisabled();
    }

    public boolean isTotpActive() {
        return "ACTIVE".equalsIgnoreCase(totpStatus) && totpSeedSet;
    }
}
