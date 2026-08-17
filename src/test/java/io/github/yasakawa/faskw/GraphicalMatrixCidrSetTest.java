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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixCidrSetTest {
    @Test
    void matchesIpv4AndIpv6NetworksWithoutResolvingHostnames() {
        final GraphicalMatrixCidrSet cidrs = GraphicalMatrixCidrSet.parse(
            "192.168.10.25/24,2001:db8:1234::/48,fe80::/10", "test.cidrs");

        assertTrue(cidrs.contains("192.168.10.0"));
        assertTrue(cidrs.contains("192.168.10.255"));
        assertFalse(cidrs.contains("192.168.11.1"));
        assertTrue(cidrs.contains("2001:db8:1234:ffff::1"));
        assertFalse(cidrs.contains("2001:db8:1235::1"));
        assertTrue(cidrs.contains("fe80::1%eth0"));
        assertFalse(cidrs.contains("localhost"));
    }

    @Test
    void rejectsAddressValuesWithoutExplicitPrefixes() {
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixCidrSet.parse("192.0.2.10", "test.cidrs"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixCidrSet.parse("example.org/24", "test.cidrs"));
    }
}
