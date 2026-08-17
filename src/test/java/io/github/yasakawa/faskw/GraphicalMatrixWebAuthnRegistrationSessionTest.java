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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixWebAuthnRegistrationSessionTest {
    @Test
    void pendingRegistrationCanBeConsumedOnlyOnce() {
        final HttpSession session = session();
        GraphicalMatrixWebAuthnRegistrationSession.initialize(
            session, "test01", "GraphicalMatrix", 2000L);

        final GraphicalMatrixWebAuthnRegistrationSession.PendingRegistration pending =
            GraphicalMatrixWebAuthnRegistrationSession.consume(session, 1000L);

        assertEquals("test01", pending.user());
        assertEquals("GraphicalMatrix", pending.expectedMethod());
        assertEquals(2000L, pending.expiresAt());
        assertNull(GraphicalMatrixWebAuthnRegistrationSession.consume(session, 1000L));
    }

    @Test
    void expiredRegistrationIsRejectedAndCleared() {
        final HttpSession session = session();
        GraphicalMatrixWebAuthnRegistrationSession.initialize(
            session, "test01", "TOTP", 999L);

        assertNull(GraphicalMatrixWebAuthnRegistrationSession.consume(session, 1000L));
        assertNull(GraphicalMatrixWebAuthnRegistrationSession.consume(session, 500L));
    }

    private static HttpSession session() {
        final Map<String, Object> attributes = new HashMap<>();
        return (HttpSession) Proxy.newProxyInstance(
            HttpSession.class.getClassLoader(),
            new Class<?>[] {HttpSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getAttribute" -> attributes.get((String) args[0]);
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                case "removeAttribute" -> {
                    attributes.remove((String) args[0]);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
