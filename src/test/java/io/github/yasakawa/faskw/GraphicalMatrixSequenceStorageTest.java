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
