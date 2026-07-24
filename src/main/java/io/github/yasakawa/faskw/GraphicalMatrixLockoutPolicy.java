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

import java.io.Serializable;

public final class GraphicalMatrixLockoutPolicy implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Level {
        NONE("none"),
        NORMAL("normal"),
        MAXIMUM("maximum");

        private final String auditValue;

        Level(final String auditValue) {
            this.auditValue = auditValue;
        }

        public String getAuditValue() {
            return auditValue;
        }
    }

    public static final class LockDecision implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Level level;
        private final long lockMillis;

        private LockDecision(final Level level, final long lockMillis) {
            this.level = level;
            this.lockMillis = lockMillis;
        }

        public boolean isLocked() {
            return level != Level.NONE;
        }

        public Level getLevel() {
            return level;
        }

        public long getLockMillis() {
            return lockMillis;
        }

        public long getLockSeconds() {
            return lockMillis / 1000L;
        }
    }

    private static final LockDecision NO_LOCK = new LockDecision(Level.NONE, 0L);

    private final int failureLimit;
    private final long lockMillis;
    private final int maxLockFailureCount;
    private final long maxLockMillis;

    public GraphicalMatrixLockoutPolicy(final int failureLimit, final long lockMillis,
            final int maxLockFailureCount, final long maxLockMillis) {
        if (failureLimit < 1) {
            throw new IllegalArgumentException("GraphicalMatrix lockout failure limit must be positive.");
        }
        if (lockMillis < 1L) {
            throw new IllegalArgumentException("GraphicalMatrix lockout duration must be positive.");
        }
        if (maxLockFailureCount <= failureLimit) {
            throw new IllegalArgumentException(
                "GraphicalMatrix maximum lock failure count must exceed the normal failure limit.");
        }
        if (maxLockMillis < lockMillis) {
            throw new IllegalArgumentException(
                "GraphicalMatrix maximum lock duration must not be shorter than the normal duration.");
        }
        this.failureLimit = failureLimit;
        this.lockMillis = lockMillis;
        this.maxLockFailureCount = maxLockFailureCount;
        this.maxLockMillis = maxLockMillis;
    }

    public LockDecision afterFailure(final int failedCount) {
        if (failedCount < failureLimit) {
            return NO_LOCK;
        }
        if (failedCount < maxLockFailureCount) {
            return new LockDecision(Level.NORMAL, lockMillis);
        }
        return new LockDecision(Level.MAXIMUM, maxLockMillis);
    }
}
