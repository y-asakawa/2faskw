package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSelfServiceConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void defaultsToDisabledWithLegacyLoginEnabled() throws Exception {
        writeProperties("");

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertFalse(config.isSelfServiceEnabled());
        assertTrue(config.isLegacyLdapLoginEnabled());
        assertEquals(600, config.getSelfServiceTransactionSeconds());
    }

    @Test
    void acceptsSelfServiceOnlyConfiguration() throws Exception {
        writeProperties("""
            graphicalmatrix.selfservice.enabled = true
            graphicalmatrix.selfservice.transactionTtlSeconds = 300
            graphicalmatrix.change.legacyLdapLoginEnabled = false
            """);

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertTrue(config.isSelfServiceEnabled());
        assertFalse(config.isLegacyLdapLoginEnabled());
        assertEquals(300, config.getSelfServiceTransactionSeconds());
    }

    @Test
    void rejectsDisabledSelfServiceAndLegacyLogin() throws Exception {
        writeProperties("""
            graphicalmatrix.selfservice.enabled = false
            graphicalmatrix.change.legacyLdapLoginEnabled = false
            """);

        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    @Test
    void rejectsUnsafeTransactionLifetime() throws Exception {
        writeProperties("graphicalmatrix.selfservice.transactionTtlSeconds = 901\n");

        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    private void writeProperties(final String value) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), value);
    }
}
