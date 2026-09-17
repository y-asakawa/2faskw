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

import java.io.Serial;
import java.io.Serializable;

/** Immutable identity of one pending TOTP enrollment transaction. */
public record GraphicalMatrixTotpEnrollmentBinding(
        String user, String registrationId, long stateVersion, long expiresAt)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public GraphicalMatrixTotpEnrollmentBinding {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("TOTP enrollment user is required.");
        }
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("TOTP registration ID is required.");
        }
        if (stateVersion < 1L) {
            throw new IllegalArgumentException("TOTP enrollment state version must be positive.");
        }
        if (expiresAt < 1L) {
            throw new IllegalArgumentException("TOTP enrollment expiry must be positive.");
        }
    }
}
