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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSpFilesTest {
    @TempDir
    Path temporary;

    @Test
    void appendsToARegularAuditFile() throws Exception {
        final Path audit = temporary.resolve("logs/sp-management-audit.log");

        GraphicalMatrixSpFiles.appendAudit(audit, "first\n");
        GraphicalMatrixSpFiles.appendAudit(audit, "second\n");

        assertEquals("first\nsecond\n", Files.readString(audit));
    }

    @Test
    void refusesToAppendThroughASymbolicLink() throws Exception {
        final Path target = Files.writeString(temporary.resolve("target.txt"), "unchanged\n");
        final Path audit = temporary.resolve("audit.log");
        Files.createSymbolicLink(audit, target);

        assertThrows(IOException.class,
            () -> GraphicalMatrixSpFiles.appendAudit(audit, "injected\n"));
        assertEquals("unchanged\n", Files.readString(target));
    }
}
