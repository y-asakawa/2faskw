package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GraphicalMatrixCliSqlCommandTest {
    Connection connection;
    @BeforeEach void prepare() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:audit-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false");
        GraphicalMatrixSequenceMigrationTool.initSchema(connection);
        try (var st = connection.createStatement()) {
            st.execute("INSERT INTO graphicalmatrix_enrollment(user_id,sequence,created_at,updated_at) VALUES ('alice','PRIVATE_SEQUENCE',1,1)");
        }
    }
    @AfterEach void close() throws Exception { connection.close(); }
    long count(String sql) throws Exception { try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); } }
    @Test void reportsStatementCountAndActualStateWithoutCredentials() throws Exception {
        final var reports = GraphicalMatrixCliSqlCommand.execute(connection, false, "enrollment", "SET_METHOD", "alice", Map.of(),
            "UPDATE graphicalmatrix_enrollment SET mfa_method='TOTP',state_version=state_version+1 WHERE user_id='alice';");
        assertEquals(1, reports.size()); assertEquals(1, reports.getFirst().changedRows());
        assertEquals("TOTP", reports.getFirst().changes().get(GraphicalMatrixCliOperationReport.Field.MFA_METHOD));
        assertEquals("1", reports.getFirst().changes().get(GraphicalMatrixCliOperationReport.Field.STATE_VERSION));
        assertFalse(reports.toString().contains("PRIVATE_SEQUENCE"));
        final var missing = GraphicalMatrixCliSqlCommand.execute(connection, false, "enrollment", "DISABLE", "nobody", Map.of(),
            "UPDATE graphicalmatrix_enrollment SET status='DISABLED' WHERE user_id='nobody';");
        assertEquals(0, missing.getFirst().matchedRows()); assertTrue(missing.getFirst().changes().isEmpty());
    }
    @Test void transactionFailureRollsBackAllRowsAndDoesNotReturnSuccessEvidence() throws Exception {
        assertThrows(SQLException.class, () -> GraphicalMatrixCliSqlCommand.execute(connection, false, "enrollment", "DISABLE", "alice", Map.of(),
            "UPDATE graphicalmatrix_enrollment SET status='DISABLED' WHERE user_id='alice'; UPDATE nonexistent SET bad=1;"));
        assertEquals(1, count("SELECT COUNT(*) FROM graphicalmatrix_enrollment WHERE status='ACTIVE'"));
    }
    @Test void csvCapturesEachTargetAndOnlyAfterCommit() throws Exception {
        final var reports = GraphicalMatrixCliSqlCommand.execute(connection, false, "csv", "IMPORT_CSV", "*", Map.of(),
            "BEGIN; SELECT 'M' AS audit_csv_action,'alice' AS audit_target,'TOTP' AS audit_method,'1' AS audit_force;"
            + " UPDATE graphicalmatrix_enrollment SET mfa_method='TOTP',state_version=state_version+1 WHERE user_id='alice';"
            + " SELECT 'D' AS audit_csv_action,'alice' AS audit_target;"
            + " UPDATE graphicalmatrix_enrollment SET status='DISABLED',state_version=state_version+1 WHERE user_id='alice'; COMMIT;");
        assertEquals(2, reports.size());
        assertEquals("M", reports.getFirst().changes().get(GraphicalMatrixCliOperationReport.Field.CSV_ACTION));
        assertEquals("ACTIVE", reports.getFirst().changes().get(GraphicalMatrixCliOperationReport.Field.STATUS));
        assertEquals("DISABLED", reports.getLast().changes().get(GraphicalMatrixCliOperationReport.Field.STATUS));
        assertEquals(1, count("SELECT COUNT(*) FROM graphicalmatrix_enrollment WHERE status='DISABLED'"));
    }
    @Test void actualReturningTargetsDoNotUseAnEarlierCount() throws Exception {
        var reports = GraphicalMatrixCliSqlCommand.execute(connection, false, "targets", "INVALIDATE_PENDING_TOTP", "*", Map.of(),
            "SELECT user_id AS audit_target FROM FINAL TABLE (UPDATE graphicalmatrix_enrollment SET totp_status='UNREGISTERED' WHERE totp_status='PENDING');");
        assertTrue(reports.isEmpty());
        connection.createStatement().execute("UPDATE graphicalmatrix_enrollment SET totp_status='PENDING' WHERE user_id='alice'"); connection.commit();
        reports = GraphicalMatrixCliSqlCommand.execute(connection, false, "targets", "INVALIDATE_PENDING_TOTP", "*", Map.of(),
            "SELECT user_id AS audit_target FROM FINAL TABLE (UPDATE graphicalmatrix_enrollment SET totp_status='UNREGISTERED' WHERE totp_status='PENDING');");
        assertEquals("alice", reports.getFirst().targetUser()); assertEquals(1, reports.getFirst().changedRows());
    }
    @Test void ddlAndQuotedSemicolonsUseStructuredSqlSplitting() throws Exception {
        GraphicalMatrixCliSqlCommand.execute(connection, false, "schema", "SCHEMA_INIT", "*", Map.of(),
            "CREATE TABLE IF NOT EXISTS audit_fixture(id INT, note VARCHAR); ALTER TABLE audit_fixture ADD COLUMN IF NOT EXISTS test INT;");
        GraphicalMatrixCliSqlCommand.execute(connection, false, "enrollment", "SET_SEQUENCE", "alice", Map.of(),
            "UPDATE graphicalmatrix_enrollment SET sequence='secret;not-a-new-statement' WHERE user_id='alice';");
        assertEquals(1, count("SELECT COUNT(*) FROM graphicalmatrix_enrollment WHERE sequence='secret;not-a-new-statement'"));
    }

    @Test void tenThousandCsvRowsProduceOneBoundedCommitBatch() throws Exception {
        connection.createStatement().execute("INSERT INTO graphicalmatrix_enrollment(user_id,sequence,created_at,updated_at) "
            + "SELECT 'batch-' || X,'PRIVATE_SEQUENCE',1,1 FROM SYSTEM_RANGE(1,10000)");
        connection.commit();
        final StringBuilder sql = new StringBuilder();
        for (int i = 1; i <= 10000; i++) {
            sql.append("SELECT 'D' AS audit_csv_action,'batch-").append(i).append("' AS audit_target;")
                .append("UPDATE graphicalmatrix_enrollment SET status='DISABLED' WHERE user_id='batch-").append(i).append("';");
        }
        final long started = System.nanoTime();
        var reports = GraphicalMatrixCliSqlCommand.execute(connection, false, "csv", "IMPORT_CSV", "*", Map.of(), sql.toString());
        assertEquals(10000, reports.size());
        assertEquals(10000, reports.stream().mapToLong(GraphicalMatrixCliOperationReport::changedRows).sum());
        assertEquals(10000, count("SELECT COUNT(*) FROM graphicalmatrix_enrollment WHERE status='DISABLED'"));
        System.out.println("audit_csv_10000_rows_ms=" + (System.nanoTime() - started) / 1_000_000);
    }

    @Test void oversizedSafeMetadataRollsBackBeforeCommit() throws Exception {
        final var reports = Collections.nCopies(35000, GraphicalMatrixCliOperationReport.changed("u".repeat(255), 1, Map.of()));
        assertThrows(java.io.IOException.class, () -> {
            connection.setAutoCommit(false);
            try (var audit = GraphicalMatrixAdminAuditClient.transaction(connection, "IMPORT_CSV")) {
                connection.createStatement().executeUpdate("UPDATE graphicalmatrix_enrollment SET status='DISABLED' WHERE user_id='alice'");
                audit.commit(reports);
            }
        });
        assertEquals(1, count("SELECT COUNT(*) FROM graphicalmatrix_enrollment WHERE status='ACTIVE'"));
    }
}
