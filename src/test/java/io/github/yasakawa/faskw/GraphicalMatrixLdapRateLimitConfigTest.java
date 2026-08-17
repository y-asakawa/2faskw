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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixLdapRateLimitConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void loadsDefaultAndCustomIpGuardSettings() throws Exception {
        writeProperties("");
        final GraphicalMatrixConfig defaults = GraphicalMatrixConfig.load(idpHome.toString());
        assertEquals(100, defaults.getChangeLdapRateLimitIpFailureLimit());
        assertEquals(60_000L, defaults.getChangeLdapRateLimitIpWindowMillis());
        assertEquals(300_000L, defaults.getChangeLdapRateLimitIpLockMillis());
        assertEquals(0, defaults.getChangeLdapRateLimitIpLimitBypassCidrs().size());
        assertFalse(defaults.isChangeLdapRateLimitIpLimitBypassed("192.168.1.1"));

        writeProperties("""
            graphicalmatrix.change.ldapRateLimit.ipFailureLimit = 250
            graphicalmatrix.change.ldapRateLimit.ipWindowSeconds = 120
            graphicalmatrix.change.ldapRateLimit.ipLockSeconds = 600
            graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs = 192.168.0.0/16,2001:db8::/32
            """);
        final GraphicalMatrixConfig custom = GraphicalMatrixConfig.load(idpHome.toString());
        assertEquals(250, custom.getChangeLdapRateLimitIpFailureLimit());
        assertEquals(120_000L, custom.getChangeLdapRateLimitIpWindowMillis());
        assertEquals(600_000L, custom.getChangeLdapRateLimitIpLockMillis());
        assertEquals(2, custom.getChangeLdapRateLimitIpLimitBypassCidrs().size());
        assertTrue(custom.isChangeLdapRateLimitIpLimitBypassed("192.168.255.254"));
        assertFalse(custom.isChangeLdapRateLimitIpLimitBypassed("192.169.0.1"));
        assertTrue(custom.isChangeLdapRateLimitIpLimitBypassed("2001:db8:1::10"));
        assertFalse(custom.isChangeLdapRateLimitIpLimitBypassed("2001:db9::10"));
        assertFalse(custom.isChangeLdapRateLimitIpLimitBypassed("trusted.example.org"));
        assertTrue(GraphicalMatrixChangeServlet.ldapIpLimitBypassed("192.168.1.10", custom));
    }

    @Test
    void rejectsNonPositiveIpGuardSettings() throws Exception {
        writeProperties("graphicalmatrix.change.ldapRateLimit.ipFailureLimit = 0\n");

        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    @Test
    void rejectsInvalidIpGuardBypassCidrs() throws Exception {
        for (final String value : new String[] {
                "192.168.0.0",
                "trusted.example.org/24",
                "192.168.0.0/33",
                "2001:db8::/129",
                "192.168.0.0/16,"
        }) {
            writeProperties("graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs = "
                + value + "\n");
            assertThrows(IllegalArgumentException.class,
                () -> GraphicalMatrixConfig.load(idpHome.toString()), value);
        }
    }

    private void writeProperties(final String value) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), value);
    }
}
