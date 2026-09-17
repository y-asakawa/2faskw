/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixTotpEnrollmentSessionTest {
    @Test
    void bindingSurvivesHttpSessionSerialization() throws Exception {
        final GraphicalMatrixTotpEnrollmentBinding binding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-a", 3L, 2000L);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(binding);
        }

        final Object restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = input.readObject();
        }

        assertEquals(binding, restored);
    }

    @Test
    void duplicateSubmissionCannotClaimTheSameRegistration() {
        final HttpSession session = session();
        final GraphicalMatrixTotpEnrollmentBinding binding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-a", 3L, 2000L);
        GraphicalMatrixTotpEnrollmentSession.initialize(session, binding, "key", "csrf");

        final GraphicalMatrixTotpEnrollmentSession.Claim first =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf", 1000L);
        final GraphicalMatrixTotpEnrollmentSession.Claim duplicate =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf", 1000L);

        assertTrue(first.isValid());
        assertEquals(binding, first.binding());
        assertFalse(duplicate.isValid());
    }

    @Test
    void retryChangesOnlyCsrfAndDoesNotExtendExpiry() {
        final HttpSession session = session();
        final GraphicalMatrixTotpEnrollmentBinding binding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-a", 3L, 2000L);
        GraphicalMatrixTotpEnrollmentSession.initialize(session, binding, "key", "csrf-1");
        final GraphicalMatrixTotpEnrollmentSession.Claim first =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf-1", 1000L);

        assertTrue(GraphicalMatrixTotpEnrollmentSession.retry(session, binding, "csrf-2"));
        final GraphicalMatrixTotpEnrollmentSession.Claim retry =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf-2", 1500L);

        assertTrue(first.isValid());
        assertTrue(retry.isValid());
        assertEquals(binding, retry.binding());
        assertEquals(2000L, retry.binding().expiresAt());
    }

    @Test
    void expiredRegistrationIsRejectedAndCleared() {
        final HttpSession session = session();
        final GraphicalMatrixTotpEnrollmentBinding binding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-a", 3L, 999L);
        GraphicalMatrixTotpEnrollmentSession.initialize(session, binding, "key", "csrf");

        final GraphicalMatrixTotpEnrollmentSession.Claim expired =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf", 1000L);
        final GraphicalMatrixTotpEnrollmentSession.Claim replay =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf", 500L);

        assertFalse(expired.isValid());
        assertTrue(expired.expired());
        assertFalse(replay.isValid());
    }

    @Test
    void registrationExpiresAtItsExactDeadline() {
        final HttpSession session = session();
        final GraphicalMatrixTotpEnrollmentBinding binding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-a", 3L, 2000L);
        GraphicalMatrixTotpEnrollmentSession.initialize(session, binding, "key", "csrf");

        final GraphicalMatrixTotpEnrollmentSession.Claim expired =
            GraphicalMatrixTotpEnrollmentSession.claim(session, "key", "csrf", 2000L);

        assertFalse(expired.isValid());
        assertTrue(expired.expired());
    }

    @Test
    void staleResponseCannotReopenOrClearANewerRegistration() {
        final HttpSession session = session();
        final GraphicalMatrixTotpEnrollmentBinding oldBinding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-a", 3L, 2000L);
        final GraphicalMatrixTotpEnrollmentBinding newBinding =
            new GraphicalMatrixTotpEnrollmentBinding("test01", "registration-b", 4L, 3000L);
        GraphicalMatrixTotpEnrollmentSession.initialize(session, oldBinding, "key-a", "csrf-a");
        assertTrue(GraphicalMatrixTotpEnrollmentSession.claim(
            session, "key-a", "csrf-a", 1000L).isValid());

        GraphicalMatrixTotpEnrollmentSession.initialize(session, newBinding, "key-b", "csrf-b");

        assertFalse(GraphicalMatrixTotpEnrollmentSession.retry(session, oldBinding, "csrf-old"));
        assertFalse(GraphicalMatrixTotpEnrollmentSession.clearIfCurrent(session, oldBinding));
        assertTrue(GraphicalMatrixTotpEnrollmentSession.claim(
            session, "key-b", "csrf-b", 1500L).isValid());
    }

    private static HttpSession session() {
        final Map<String, Object> attributes = new HashMap<>();
        return (HttpSession) Proxy.newProxyInstance(
            HttpSession.class.getClassLoader(), new Class<?>[] {HttpSession.class},
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
