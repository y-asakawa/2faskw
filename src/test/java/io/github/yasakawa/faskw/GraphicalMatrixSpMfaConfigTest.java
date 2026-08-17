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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixSpMfaConfigTest {
    @Test
    void entityIdCannotContainPolicyDelimiters() {
        assertDoesNotThrow(() -> GraphicalMatrixSpMfaConfig.validateEntityId(
            "https://sp.example.org/shibboleth"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMfaConfig.validateEntityId("urn:one,urn:two"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMfaConfig.validateEntityId("urn:one;urn:two"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMfaConfig.validateEntityId("urn:one|cidr"));
    }
}
