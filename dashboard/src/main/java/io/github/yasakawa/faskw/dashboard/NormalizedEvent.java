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
package io.github.yasakawa.faskw.dashboard;

import java.time.Instant;

public record NormalizedEvent(
        int schemaVersion,
        String eventId,
        Instant occurredAt,
        Instant receivedAt,
        String nodeId,
        String event,
        String result,
        String reason,
        String userRef,
        String sourceNetwork,
        Long lockedUntil,
        String parseStatus,
        String sourceGeneration,
        long sourceOffset,
        String lineDigest) {
}
