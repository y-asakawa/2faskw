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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixLdapRateLimitBypassTest {
    @TempDir
    Path idpHome;

    @Test
    void trustedCidrSkipsOnlyTheIndependentIpGuard() throws Exception {
        final GraphicalMatrixConfig config = config("192.168.0.0/16");
        final HttpServletRequest request = request("192.168.50.10");

        GraphicalMatrixChangeServlet.recordLdapFailure(request, "nat-user-a", config);

        assertTrue(GraphicalMatrixChangeServlet.ldapRateLimited(request, "nat-user-a", config));
        assertFalse(GraphicalMatrixChangeServlet.ldapRateLimited(request, "nat-user-b", config));
    }

    @Test
    void untrustedAddressStillUsesTheIndependentIpGuard() throws Exception {
        final GraphicalMatrixConfig config = config("192.168.0.0/16");
        final HttpServletRequest request = request("198.51.100.77");

        GraphicalMatrixChangeServlet.recordLdapFailure(request, "external-user-a", config);

        assertTrue(GraphicalMatrixChangeServlet.ldapRateLimited(
            request, "external-user-b", config));
    }

    private GraphicalMatrixConfig config(final String bypassCidrs) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), """
            graphicalmatrix.change.ldapRateLimit.enabled = true
            graphicalmatrix.change.ldapRateLimit.failureLimit = 1
            graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
            graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
            graphicalmatrix.change.ldapRateLimit.key = ip-user
            graphicalmatrix.change.ldapRateLimit.ipFailureLimit = 1
            graphicalmatrix.change.ldapRateLimit.ipWindowSeconds = 60
            graphicalmatrix.change.ldapRateLimit.ipLockSeconds = 300
            graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs = %s
            """.formatted(bypassCidrs));
        return GraphicalMatrixConfig.load(idpHome.toString());
    }

    private static HttpServletRequest request(final String remoteAddress) {
        return (HttpServletRequest) Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> "getRemoteAddr".equals(method.getName())
                ? remoteAddress
                : defaultValue(method.getReturnType()));
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
        if (type == int.class || type == short.class || type == byte.class || type == char.class) {
            return 0;
        }
        if (type == double.class || type == float.class) {
            return 0.0;
        }
        return null;
    }
}
