package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixRepositoryStateTest {
    @TempDir
    Path idpHome;

    private String jdbcUrl;
    private GraphicalMatrixRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        resetRepositorySchemaInitialized();
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        jdbcUrl = "jdbc:h2:mem:state-" + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        Files.writeString(conf.resolve("db.properties"),
            "graphicalmatrix.db.driver=org.h2.Driver\n"
            + "graphicalmatrix.db.url=" + jdbcUrl + "\n"
            + "graphicalmatrix.db.user=sa\n"
            + "graphicalmatrix.db.password=\n"
            + "graphicalmatrix.db.autoInit=true\n"
            + "graphicalmatrix.db.pool.enabled=false\n");
        Files.writeString(conf.resolve("graphicalmatrix.properties"),
            "graphicalmatrix.sequence.storage=plaintext\n");
        System.setProperty("idp.home", idpHome.toString());
        repository = new GraphicalMatrixRepository(idpHome.toString());
        repository.findEnrollment("missing");
        insertEnrollment();
    }

    private static void resetRepositorySchemaInitialized() throws Exception {
        final Field field = GraphicalMatrixRepository.class.getDeclaredField("SCHEMA_INITIALIZED");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(null)).set(false);
    }

    @AfterEach
    void tearDown() {
        GraphicalMatrixDataSource.close();
        System.clearProperty("idp.home");
    }

    @Test
    void updateRequiresActiveEnrollmentAndCurrentStateVersion() throws Exception {
        final GraphicalMatrixEnrollment verified = repository.findEnrollment("alice");

        assertTrue(repository.updateMfaMethod("alice", "TOTP", 1250L));
        final GraphicalMatrixEnrollment methodChanged = repository.findEnrollment("alice");
        assertEquals(verified.getStateVersion() + 1, methodChanged.getStateVersion());
        assertFalse(repository.updateSequence("alice", List.of("g2"), 1400L,
            verified.getStateVersion(), true, false));

        assertTrue(repository.updateMfaMethod("alice", "GraphicalMatrix", 1450L));
        final GraphicalMatrixEnrollment graphicalsRestored = repository.findEnrollment("alice");
        assertTrue(repository.updateSequence("alice", List.of("g2"), 1500L,
            graphicalsRestored.getStateVersion(), true, false));
        final GraphicalMatrixEnrollment updated = repository.findEnrollment("alice");
        assertEquals("g2", updated.getSequence());
        assertEquals(graphicalsRestored.getStateVersion() + 1, updated.getStateVersion());

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE graphicalmatrix_enrollment "
                 + "SET status = 'DISABLED', state_version = state_version + 1 WHERE user_id = ?")) {
            statement.setString(1, "alice");
            statement.executeUpdate();
        }

        assertFalse(repository.updateSequence("alice", List.of("g2"), 2000L,
            updated.getStateVersion(), true, false));
        assertFalse(repository.findEnrollment("alice").isActive());
        assertEquals("g2", updated.getSequence());
    }

    @Test
    void findEnrollmentReturnsStoredSequenceForProtectedRuntimeValidation() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.writeString(conf.resolve("graphicalmatrix.properties"),
            "graphicalmatrix.sequence.storage=keyword\n"
            + "graphicalmatrix.sequence.keyword=test-only-keyword\n"
            + "graphicalmatrix.columns=1\n"
            + "graphicalmatrix.rows=1\n"
            + "graphicalmatrix.graphicals=g1\n"
            + "graphicalmatrix.choice=1\n");
        repository = new GraphicalMatrixRepository(idpHome.toString());
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertTrue(repository.updateSequence("alice", List.of("g1"), 1600L,
            repository.findEnrollment("alice").getStateVersion(), true, false));

        final GraphicalMatrixEnrollment enrollment = repository.findEnrollment("alice");
        assertNotEquals("g1", enrollment.getSequence());
        assertTrue(enrollment.getSequence().startsWith("kw1:"));
        assertTrue(repository.sequenceUsable(enrollment.getSequence(), config));
    }

    private void insertEnrollment() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO graphicalmatrix_enrollment "
                 + "(user_id, sequence, initial_sequence, status, failed_count, locked_until, "
                 + "mfa_method, totp_status, totp_registered_at, last_success_at, "
                 + "force_sequence_change, state_version, created_at, updated_at) "
                 + "VALUES (?, ?, ?, 'ACTIVE', 0, 0, 'GraphicalMatrix', 'UNREGISTERED', "
                 + "0, 0, 1, 0, 1000, 1000)")) {
            statement.setString(1, "alice");
            statement.setString(2, "g1");
            statement.setString(3, "g1");
            statement.executeUpdate();
        }
    }
}
