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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixH2CommandTest {
    private static final String PASSWORD = "non-empty-test-password";

    @TempDir
    Path temporary;

    @Test
    void readsPasswordFromStandardInputForSqlAndScriptCommands() throws Exception {
        final String url = "jdbc:h2:file:" + temporary.resolve("credential-test");
        try (var ignored = DriverManager.getConnection(url, "sa", PASSWORD)) {
            // Initialize the password-protected database before running the command bridge.
        }
        final Path script = Files.writeString(temporary.resolve("test.sql"),
            "CREATE TABLE sample(result_value INT); "
                + "INSERT INTO sample(result_value) VALUES (42);\n");

        invoke("script", url, "sa", script.toString());
        final String output = invoke("sql", url, "sa", "SELECT result_value FROM sample");

        assertTrue(output.contains("42"));
    }

    private static String invoke(final String mode, final String url, final String user,
            final String payload) throws Exception {
        final InputStream originalInput = System.in;
        final PrintStream originalOutput = System.out;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(
                (PASSWORD + "\n").getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            GraphicalMatrixH2Command.main(new String[] {mode, url, user, payload});
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            System.setIn(originalInput);
            System.setOut(originalOutput);
        }
    }
}
