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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSequenceStorageTest {
    @TempDir
    Path idpHome;

    @Test
    void protectedModeRejectsLegacyPlaintextAtRuntime() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("graphicalmatrix.properties"),
            "graphicalmatrix.sequence.storage=hash\n"
            + "graphicalmatrix.sequence.pepper=test-only-pepper\n");

        final GraphicalMatrixSequenceStorage storage =
            GraphicalMatrixSequenceStorage.load(idpHome.toString());
        final String protectedSequence = storage.encode(List.of("g1", "g2"), true, false);

        assertFalse(storage.acceptedForRuntime("g1,g2"));
        assertFalse(storage.matches("g1,g2", List.of("g1", "g2"), true, false));
        assertTrue(storage.acceptedForRuntime(protectedSequence));
        assertTrue(storage.matches(protectedSequence, List.of("g1", "g2"), true, false));
    }
}
