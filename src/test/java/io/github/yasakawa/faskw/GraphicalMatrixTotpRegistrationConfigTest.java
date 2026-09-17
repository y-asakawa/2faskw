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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixTotpRegistrationConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void defaultsToThreeMinutes() throws Exception {
        writeProperties("");

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertEquals(180, config.getTotpRegistrationTtlSeconds());
        assertEquals(180_000L, config.getTotpRegistrationTtlMillis());
    }

    @Test
    void acceptsDocumentedBoundaries() throws Exception {
        writeProperties("graphicalmatrix.totp.registrationTtlSeconds = 30\n");
        assertEquals(30, GraphicalMatrixConfig.load(idpHome.toString())
            .getTotpRegistrationTtlSeconds());

        writeProperties("graphicalmatrix.totp.registrationTtlSeconds = 900\n");
        assertEquals(900, GraphicalMatrixConfig.load(idpHome.toString())
            .getTotpRegistrationTtlSeconds());
    }

    @Test
    void rejectsOutOfRangeOrNonNumericValues() throws Exception {
        assertInvalid("graphicalmatrix.totp.registrationTtlSeconds = 29\n");
        assertInvalid("graphicalmatrix.totp.registrationTtlSeconds = 901\n");
        assertInvalid("graphicalmatrix.totp.registrationTtlSeconds = three-minutes\n");
    }

    private void assertInvalid(final String properties) throws Exception {
        writeProperties(properties);
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    private void writeProperties(final String value) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), value);
    }
}
