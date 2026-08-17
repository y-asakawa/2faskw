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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixLdapLoginRateLimiter {
    private static final long MAXIMUM_CLEANUP_INTERVAL_MILLIS = 60_000L;
    private final int maximumEntries;
    private final Map<String, State> states = new HashMap<>();
    private final Set<String> evictableKeys = new LinkedHashSet<>();
    private final Set<String> allKeys = new LinkedHashSet<>();
    private long nextCleanupMillis;

    GraphicalMatrixLdapLoginRateLimiter(final int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    synchronized boolean isLimited(final String key, final long now, final long maximumAge) {
        cleanupIfDue(now, maximumAge);
        final State state = states.get(key);
        if (state == null) {
            return false;
        }
        if (state.lockedUntilMillis > now) {
            return true;
        }
        if (state.lockedUntilMillis > 0L) {
            states.remove(key);
            allKeys.remove(key);
        }
        return false;
    }

    synchronized void recordFailure(final String key, final long now, final long windowMillis,
            final int failureLimit, final long lockMillis, final long maximumAge) {
        cleanupIfDue(now, maximumAge);
        State state = states.get(key);
        if (state != null && state.lockedUntilMillis > now) {
            return;
        }
        if (state != null && state.lockedUntilMillis > 0L) {
            states.remove(key);
            evictableKeys.remove(key);
            allKeys.remove(key);
            state = null;
        }
        if (state == null) {
            if (states.size() >= maximumEntries) {
                evictOldestUnlocked();
                if (states.size() >= maximumEntries) {
                    evictOldestTracked();
                }
            }
            state = new State(now);
            states.put(key, state);
            allKeys.add(key);
        }
        evictableKeys.remove(key);
        evictableKeys.add(key);
        while (!state.failures.isEmpty()
                && now - state.failures.peekFirst().longValue() > windowMillis) {
            state.failures.removeFirst();
        }
        state.failures.addLast(Long.valueOf(now));
        state.lastSeenMillis = now;
        if (state.failures.size() >= failureLimit) {
            state.lockedUntilMillis = now + lockMillis;
            state.failures.clear();
            evictableKeys.remove(key);
        }
    }

    synchronized void clear(final String key) {
        states.remove(key);
        evictableKeys.remove(key);
        allKeys.remove(key);
    }

    synchronized int size() {
        return states.size();
    }

    synchronized boolean contains(final String key) {
        return states.containsKey(key);
    }

    private void evictOldestUnlocked() {
        final var iterator = evictableKeys.iterator();
        if (iterator.hasNext()) {
            final String oldestKey = iterator.next();
            iterator.remove();
            states.remove(oldestKey);
            allKeys.remove(oldestKey);
        }
    }

    private void evictOldestTracked() {
        final var iterator = allKeys.iterator();
        if (iterator.hasNext()) {
            final String oldestKey = iterator.next();
            iterator.remove();
            evictableKeys.remove(oldestKey);
            states.remove(oldestKey);
        }
    }

    private void cleanupIfDue(final long now, final long maximumAge) {
        if (now < nextCleanupMillis) {
            return;
        }
        states.entrySet().removeIf(entry -> {
            final State state = entry.getValue();
            final boolean expired = state.lockedUntilMillis <= now
                && now - state.lastSeenMillis > maximumAge;
            if (expired) {
                evictableKeys.remove(entry.getKey());
                allKeys.remove(entry.getKey());
            }
            return expired;
        });
        final long interval = Math.max(1L,
            Math.min(maximumAge, MAXIMUM_CLEANUP_INTERVAL_MILLIS));
        nextCleanupMillis = now > Long.MAX_VALUE - interval
            ? Long.MAX_VALUE : now + interval;
    }

    private static final class State {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long lockedUntilMillis;
        private long lastSeenMillis;

        private State(final long now) {
            lastSeenMillis = now;
        }
    }
}
