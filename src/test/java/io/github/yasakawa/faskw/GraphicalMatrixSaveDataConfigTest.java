package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSaveDataConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void defaultsToDbWhenUnset() throws Exception {
        Files.createDirectories(idpHome.resolve("conf/graphicalmatrix"));
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/graphicalmatrix.properties"), "");

        assertEquals("db", GraphicalMatrixSaveDataConfig.load(idpHome.toString()).mode());
    }

    @Test
    void acceptsLdapMode() throws Exception {
        Files.createDirectories(idpHome.resolve("conf/graphicalmatrix"));
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.savedata = ldap\n");

        assertTrue(GraphicalMatrixSaveDataConfig.load(idpHome.toString()).isLdap());
    }

    @Test
    void rejectsUnsupportedMode() throws Exception {
        Files.createDirectories(idpHome.resolve("conf/graphicalmatrix"));
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.savedata = file\n");

        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSaveDataConfig.load(idpHome.toString()));
    }
}
