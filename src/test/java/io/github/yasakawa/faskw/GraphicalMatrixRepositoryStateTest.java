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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        assertTrue(repository.updateMfaMethod("alice", "WebAuthn", 1450L));
        final GraphicalMatrixEnrollment webauthnSelected = repository.findEnrollment("alice");
        assertTrue(repository.updateSequence("alice", List.of("g2"), 1500L,
            webauthnSelected.getStateVersion(), true, false));
        final GraphicalMatrixEnrollment updated = repository.findEnrollment("alice");
        assertEquals("g2", updated.getSequence());
        assertEquals(webauthnSelected.getStateVersion() + 1, updated.getStateVersion());

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
    void updateMfaMethodIfCurrentRequiresExpectedVersionAndUnlockedEnrollment() throws Exception {
        final GraphicalMatrixEnrollment verified = repository.findEnrollment("alice");

        assertFalse(repository.updateMfaMethodIfCurrent("alice", "TOTP", 1200L,
            verified.getStateVersion() + 1));
        assertEquals("GraphicalMatrix", findMethod("alice"));

        lockEnrollment("alice", 5000L);
        assertFalse(repository.updateMfaMethodIfCurrent("alice", "TOTP", 1300L,
            verified.getStateVersion()));
        final GraphicalMatrixEnrollment locked = repository.findEnrollment("alice");
        assertEquals(5000L, locked.getLockedUntil());
        assertEquals("GraphicalMatrix", findMethod("alice"));
    }

    @Test
    void totpRegistrationDoesNotStartOrActivateWhileLocked() throws Exception {
        configureTotpPending("alice", 5000L);

        assertNull(repository.prepareTotpRegistration("alice", 1200L));

        final GraphicalMatrixVerifyResult result =
            repository.verifyAndActivateTotp("alice", "000000", 1200L);
        assertFalse(result.isSuccess());
        assertEquals("LOCKED", result.getAuditResult());
        assertEquals("PENDING", findTotpStatus("alice"));
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

    @Test
    void appliesNormalAndMaximumLockoutsAndResetsAfterSuccess() throws Exception {
        final GraphicalMatrixLockoutPolicy policy =
            new GraphicalMatrixLockoutPolicy(3, 120000L, 6, 3600000L);
        final List<String> displayOrder = List.of("g1", "g2");
        final List<String> incorrect = List.of("g2");

        GraphicalMatrixVerifyResult result = repository.verify(
            "alice", incorrect, displayOrder, 2000L, policy, true, false);
        assertEquals("FAIL", result.getAuditResult());
        assertTrue(result.getAuditDetail().contains("lock_level=none"));

        result = repository.verifyForSequenceChange(
            "alice", incorrect, displayOrder, 3000L, policy, true, false);
        assertEquals("FAIL", result.getAuditResult());

        result = repository.verify(
            "alice", incorrect, displayOrder, 4000L, policy, true, false);
        assertNormalLock(result, 124000L, 3);

        result = repository.verify(
            "alice", incorrect, displayOrder, 124001L, policy, true, false);
        assertNormalLock(result, 244001L, 4);

        result = repository.verify(
            "alice", incorrect, displayOrder, 244002L, policy, true, false);
        assertNormalLock(result, 364002L, 5);

        result = repository.verify(
            "alice", incorrect, displayOrder, 364003L, policy, true, false);
        assertMaximumLock(result, 3964003L, 6);

        result = repository.verify(
            "alice", incorrect, displayOrder, 3964004L, policy, true, false);
        assertMaximumLock(result, 7564004L, 7);

        result = repository.verify(
            "alice", List.of("g1"), displayOrder, 7564005L, policy, true, false);
        assertTrue(result.isSuccess());
        final GraphicalMatrixEnrollment enrollment = repository.findEnrollment("alice");
        assertEquals(0, enrollment.getFailedCount());
        assertEquals(0L, enrollment.getLockedUntil());
    }

    private void assertNormalLock(final GraphicalMatrixVerifyResult result,
            final long expectedLockedUntil, final int expectedFailures) throws Exception {
        assertLock(result, expectedLockedUntil, expectedFailures, "normal", 120L);
    }

    private void assertMaximumLock(final GraphicalMatrixVerifyResult result,
            final long expectedLockedUntil, final int expectedFailures) throws Exception {
        assertLock(result, expectedLockedUntil, expectedFailures, "maximum", 3600L);
    }

    private void assertLock(final GraphicalMatrixVerifyResult result,
            final long expectedLockedUntil, final int expectedFailures,
            final String level, final long seconds) throws Exception {
        assertEquals("LOCKED", result.getAuditResult());
        assertEquals(expectedLockedUntil, result.getLockedUntil());
        assertTrue(result.getAuditDetail().contains("failed_count=" + expectedFailures));
        assertTrue(result.getAuditDetail().contains("lock_level=" + level));
        assertTrue(result.getAuditDetail().contains("lock_seconds=" + seconds));
        final GraphicalMatrixEnrollment enrollment = repository.findEnrollment("alice");
        assertEquals(expectedFailures, enrollment.getFailedCount());
        assertEquals(expectedLockedUntil, enrollment.getLockedUntil());
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

    private void lockEnrollment(final String user, final long lockedUntil) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE graphicalmatrix_enrollment SET locked_until = ? WHERE user_id = ?")) {
            statement.setLong(1, lockedUntil);
            statement.setString(2, user);
            statement.executeUpdate();
        }
    }

    private void configureTotpPending(final String user, final long lockedUntil) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE graphicalmatrix_enrollment "
                 + "SET mfa_method = 'TOTP', totp_seed = 'INVALID', totp_status = 'PENDING', "
                 + "locked_until = ? WHERE user_id = ?")) {
            statement.setLong(1, lockedUntil);
            statement.setString(2, user);
            statement.executeUpdate();
        }
    }

    private String findMethod(final String user) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT mfa_method FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
            statement.setString(1, user);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("mfa_method");
            }
        }
    }

    private String findTotpStatus(final String user) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT totp_status FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
            statement.setString(1, user);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("totp_status");
            }
        }
    }
}
