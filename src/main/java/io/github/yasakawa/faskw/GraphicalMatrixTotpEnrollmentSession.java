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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.http.HttpSession;

/** One-request-at-a-time browser binding for a pending TOTP enrollment. */
public final class GraphicalMatrixTotpEnrollmentSession {
    private static final String PREFIX = "totpEnroll.";
    private static final String BINDING = PREFIX + "binding";
    private static final String KEY = PREFIX + "key";
    private static final String CSRF = PREFIX + "csrfToken";
    private static final String AUTHORIZED = PREFIX + "selfServiceAuthorized";
    private static final String IN_FLIGHT = PREFIX + "inFlight";

    private GraphicalMatrixTotpEnrollmentSession() {
    }

    public static void initialize(final HttpSession session,
            final GraphicalMatrixTotpEnrollmentBinding binding,
            final String key, final String csrfToken) {
        synchronized (session) {
            clear(session);
            session.setAttribute(BINDING, binding);
            session.setAttribute(KEY, key);
            session.setAttribute(CSRF, csrfToken);
            session.setAttribute(AUTHORIZED, Boolean.TRUE);
            session.setAttribute(IN_FLIGHT, Boolean.FALSE);
        }
    }

    public static Claim claim(final HttpSession session, final String submittedKey,
            final String submittedCsrf, final long now) {
        if (session == null) {
            return Claim.invalid("session_missing");
        }
        synchronized (session) {
            final Object binding = session.getAttribute(BINDING);
            final Object key = session.getAttribute(KEY);
            final Object csrf = session.getAttribute(CSRF);
            if (!(binding instanceof GraphicalMatrixTotpEnrollmentBinding value)
                    || !(key instanceof String keyValue)
                    || !(csrf instanceof String csrfValue)
                    || !Boolean.TRUE.equals(session.getAttribute(AUTHORIZED))
                    || Boolean.TRUE.equals(session.getAttribute(IN_FLIGHT))
                    || !constantTimeEquals(keyValue, submittedKey)
                    || !constantTimeEquals(csrfValue, submittedCsrf)) {
                return Claim.invalid("invalid_registration_session");
            }
            if (value.expiresAt() <= now) {
                clear(session);
                return Claim.expiredClaim();
            }
            session.setAttribute(IN_FLIGHT, Boolean.TRUE);
            return Claim.valid(value, keyValue);
        }
    }

    public static boolean retry(final HttpSession session,
            final GraphicalMatrixTotpEnrollmentBinding binding, final String csrfToken) {
        synchronized (session) {
            final Object current = session.getAttribute(BINDING);
            if (binding.equals(current)) {
                session.setAttribute(CSRF, csrfToken);
                session.setAttribute(IN_FLIGHT, Boolean.FALSE);
                return true;
            }
            return false;
        }
    }

    public static boolean clearIfCurrent(final HttpSession session,
            final GraphicalMatrixTotpEnrollmentBinding binding) {
        if (session == null || binding == null) {
            return false;
        }
        synchronized (session) {
            if (!binding.equals(session.getAttribute(BINDING))) {
                return false;
            }
            clear(session);
            return true;
        }
    }

    public static void clear(final HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(BINDING);
        session.removeAttribute(KEY);
        session.removeAttribute(CSRF);
        session.removeAttribute(AUTHORIZED);
        session.removeAttribute(IN_FLIGHT);
        // Remove pre-v1.3.5 attributes during rolling upgrades.
        session.removeAttribute(PREFIX + "user");
        session.removeAttribute(PREFIX + "expiresAt");
        session.removeAttribute(PREFIX + "used");
    }

    private static boolean constantTimeEquals(final String expected, final String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    public record Claim(GraphicalMatrixTotpEnrollmentBinding binding, String key,
            String error, boolean expired) {
        static Claim valid(final GraphicalMatrixTotpEnrollmentBinding binding, final String key) {
            return new Claim(binding, key, "", false);
        }

        static Claim invalid(final String error) {
            return new Claim(null, "", error, false);
        }

        static Claim expiredClaim() {
            return new Claim(null, "", "registration_expired", true);
        }

        public boolean isValid() {
            return binding != null;
        }
    }
}
