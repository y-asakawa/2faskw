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

import jakarta.servlet.http.HttpSession;

/** One-time intent consumed after the official WebAuthn Plugin registers a credential. */
public final class GraphicalMatrixWebAuthnRegistrationSession {
    public static final String REGISTRATION_PATH = "/profile/admin/webauthn-registration";

    private static final String PREFIX = "graphicalmatrixWebAuthnRegistration.";
    private static final String USER = PREFIX + "user";
    private static final String EXPECTED_METHOD = PREFIX + "expectedMethod";
    private static final String EXPIRES_AT = PREFIX + "expiresAt";
    private static final String NONCE = PREFIX + "nonce";

    private GraphicalMatrixWebAuthnRegistrationSession() {
    }

    public static void initialize(final HttpSession session, final String user,
            final String expectedMethod, final long expiresAt) {
        clear(session);
        session.setAttribute(USER, user);
        session.setAttribute(EXPECTED_METHOD, expectedMethod);
        session.setAttribute(EXPIRES_AT, Long.valueOf(expiresAt));
        session.setAttribute(NONCE, GraphicalMatrixSupport.token());
    }

    public static PendingRegistration consume(final HttpSession session, final long now) {
        if (session == null) {
            return null;
        }
        synchronized (session) {
            final Object user = session.getAttribute(USER);
            final Object expectedMethod = session.getAttribute(EXPECTED_METHOD);
            final Object expiresAt = session.getAttribute(EXPIRES_AT);
            final Object nonce = session.getAttribute(NONCE);
            clear(session);

            if (!(user instanceof String userValue)
                    || !(expectedMethod instanceof String expectedMethodValue)
                    || !(expiresAt instanceof Long expiresAtValue)
                    || !(nonce instanceof String nonceValue)
                    || userValue.isBlank()
                    || expectedMethodValue.isBlank()
                    || nonceValue.isBlank()
                    || expiresAtValue.longValue() < now) {
                return null;
            }
            return new PendingRegistration(userValue, expectedMethodValue,
                expiresAtValue.longValue());
        }
    }

    public static void clear(final HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(USER);
        session.removeAttribute(EXPECTED_METHOD);
        session.removeAttribute(EXPIRES_AT);
        session.removeAttribute(NONCE);
    }

    public record PendingRegistration(String user, String expectedMethod, long expiresAt) {
    }
}
