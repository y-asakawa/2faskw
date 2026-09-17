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

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixWebAuthnCredentialLookupTest {
    private static final String ROOT_CONTEXT =
        "org.springframework.web.context.WebApplicationContext.ROOT";

    @Test
    void reportsPresentForARegisteredCredential() {
        final FakeApplicationContext applicationContext = new FakeApplicationContext(Map.of(
            "shibboleth.authn.WebAuthn.CredentialRepository",
            new FakeRepository(Set.of("credential"))));

        assertEquals(GraphicalMatrixWebAuthnCredentialLookup.Result.PRESENT,
            GraphicalMatrixWebAuthnCredentialLookup.lookup(
                servletContext(applicationContext), "test01"));
    }

    @Test
    void reportsAbsentForAnEmptyRepository() {
        final FakeApplicationContext applicationContext = new FakeApplicationContext(Map.of(
            "shibboleth.authn.WebAuthn.DefaultCredentialRepository",
            new FakeRepository(Set.of())));

        assertEquals(GraphicalMatrixWebAuthnCredentialLookup.Result.ABSENT,
            GraphicalMatrixWebAuthnCredentialLookup.lookup(
                servletContext(applicationContext), "test01"));
    }

    @Test
    void reportsUnavailableWhenThePluginContextIsMissing() {
        assertEquals(GraphicalMatrixWebAuthnCredentialLookup.Result.UNAVAILABLE,
            GraphicalMatrixWebAuthnCredentialLookup.lookup(servletContext(null), "test01"));
    }

    private static ServletContext servletContext(final Object applicationContext) {
        return (ServletContext) Proxy.newProxyInstance(
            ServletContext.class.getClassLoader(), new Class<?>[] {ServletContext.class},
            (proxy, method, args) -> {
                if ("getAttribute".equals(method.getName())
                        && ROOT_CONTEXT.equals(args[0])) {
                    return applicationContext;
                }
                return defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    public static final class FakeApplicationContext {
        private final Map<String, Object> beans;

        FakeApplicationContext(final Map<String, Object> beans) {
            this.beans = beans;
        }

        public Object getBean(final String name) {
            final Object bean = beans.get(name);
            if (bean == null) {
                throw new IllegalArgumentException("missing bean: " + name);
            }
            return bean;
        }
    }

    public static final class FakeRepository {
        private final Set<String> registrations;

        FakeRepository(final Set<String> registrations) {
            this.registrations = registrations;
        }

        public Set<String> getRegistrationsByUsername(final String user) {
            return registrations;
        }
    }
}
