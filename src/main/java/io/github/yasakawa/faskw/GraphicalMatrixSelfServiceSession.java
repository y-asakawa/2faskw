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

/** One-time handoff between the authenticated IdP profile and the change servlet. */
public final class GraphicalMatrixSelfServiceSession {
    private static final String PREFIX = "graphicalmatrixSelfService.";
    private static final String USER = PREFIX + "user";
    private static final String EXPIRES_AT = PREFIX + "expiresAt";
    private static final String NONCE = PREFIX + "nonce";
    private static final String PROFILE = PREFIX + "profile";

    private GraphicalMatrixSelfServiceSession() {
    }

    public static void initialize(final HttpSession session, final String user, final long expiresAt) {
        clear(session);
        session.setAttribute(USER, user);
        session.setAttribute(EXPIRES_AT, Long.valueOf(expiresAt));
        session.setAttribute(NONCE, GraphicalMatrixSupport.token());
        session.setAttribute(PROFILE, GraphicalMatrixSelfServiceAuthentication.PROFILE_ID);
    }

    public static Handoff consume(final HttpSession session, final long now) {
        if (session == null) {
            return null;
        }
        final Object user = session.getAttribute(USER);
        final Object expiresAt = session.getAttribute(EXPIRES_AT);
        final Object nonce = session.getAttribute(NONCE);
        final Object profile = session.getAttribute(PROFILE);
        clear(session);

        if (!(user instanceof String)
                || !(expiresAt instanceof Long)
                || !(nonce instanceof String)
                || !GraphicalMatrixSelfServiceAuthentication.PROFILE_ID.equals(profile)
                || ((String) user).isBlank()
                || ((String) nonce).isBlank()
                || ((Long) expiresAt).longValue() < now) {
            return null;
        }
        return new Handoff((String) user, ((Long) expiresAt).longValue());
    }

    public static void clear(final HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(USER);
        session.removeAttribute(EXPIRES_AT);
        session.removeAttribute(NONCE);
        session.removeAttribute(PROFILE);
    }

    public static final class Handoff {
        private final String user;
        private final long expiresAt;

        private Handoff(final String user, final long expiresAt) {
            this.user = user;
            this.expiresAt = expiresAt;
        }

        public String getUser() {
            return user;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }
}
