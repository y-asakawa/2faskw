package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixAdminAuditStateTest {
    @TempDir Path home;
    Path log;
    GraphicalMatrixAdminAuditWriter writer;
    @BeforeEach void prepare() throws Exception {
        log = Files.createFile(home.resolve("audit.log"));
        Path lock = Files.createFile(home.resolve("audit.lock"));
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----"));
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-rw----"));
        final Properties p = new Properties();
        p.setProperty("graphicalmatrix.admin.audit.logFile", log.toString());
        p.setProperty("graphicalmatrix.admin.audit.lockFile", lock.toString());
        writer = new GraphicalMatrixAdminAuditWriter(p, ((Number) Files.getAttribute(home, "unix:uid")).longValue(),
            Files.readAttributes(home, PosixFileAttributes.class).group().getName());
    }
    GraphicalMatrixAdminAuditTool.State state(String... args) throws Exception {
        final var s = new GraphicalMatrixAdminAuditTool.State(home, args, writer);
        s.write("ADMIN_CLI_START", "STARTED", "NOT_ATTEMPTED", Map.of()); return s;
    }
    void commit(GraphicalMatrixAdminAuditTool.State s, long rows) throws Exception {
        s.accept("BEGIN", "ENROLLMENT_WRITE", "NOT_ATTEMPTED", List.of());
        s.accept("BATCH", "ENROLLMENT_WRITE", "COMMITTED", List.of(GraphicalMatrixCliOperationReport.changed("alice", rows,
            rows == 0 ? Map.of() : Map.of(GraphicalMatrixCliOperationReport.Field.SEQUENCE_CHANGED, "true"))));
    }
    String end() throws Exception { return Files.readAllLines(log).getLast(); }

    @Test void canonicalAliasesAndApplyDetection() {
        assertEquals("RESET", GraphicalMatrixAdminAuditTool.action(new String[]{"alice", "RESET", "-y"}));
        assertEquals("UNLOCK", GraphicalMatrixAdminAuditTool.action(new String[]{"reset-failures", "alice"}));
        assertEquals("PLAN", GraphicalMatrixAdminAuditTool.mode("IMPORT_CSV", new String[]{"csv", "input.csv"}));
        assertEquals("PLAN", GraphicalMatrixAdminAuditTool.mode("IMPORT_CSV", new String[]{"csv", "YES"}));
        assertEquals("APPLY", GraphicalMatrixAdminAuditTool.mode("IMPORT_CSV", new String[]{"csv", "input.csv", "-y"}));
    }
    @Test void successAndNoMatchAreDistinct() throws Exception {
        var s = state("set-method", "alice", "TOTP"); commit(s, 0); assertEquals(0, s.finish(0));
        assertTrue(end().contains("result=NO_MATCH db_result=COMMITTED"));
        s = state("alice", "RESET", "-y"); commit(s, 1); assertEquals(0, s.finish(0));
        assertTrue(end().contains("result=SUCCESS db_result=COMMITTED"));
        assertTrue(end().contains("action=RESET"));
    }
    @Test void cancelledResetIsNotSuccessfulWrite() throws Exception {
        var s = state("alice", "RESET"); s.accept("MARK", "CANCELLED", "NOT_ATTEMPTED", List.of());
        assertEquals(0, s.finish(0)); assertTrue(end().contains("result=CANCELLED db_result=NOT_ATTEMPTED"));
    }
    @Test void displayFailureDoesNotPretendRollback() throws Exception {
        var s = state("set-method", "alice", "TOTP"); commit(s, 1);
        s.accept("MARK", "DISPLAY", "NOT_ATTEMPTED", List.of());
        assertEquals(1, s.finish(74));
        assertTrue(end().contains("result=FAILED db_result=COMMITTED"));
        assertTrue(end().contains("reason_code=DISPLAY_FAILED"));
    }
    @Test void rollbackAfterPreviousCommitIsPartialAndUnknownStaysUnknown() throws Exception {
        var s = state("delete", "alice"); commit(s, 1);
        s.accept("BEGIN", "CLEANUP_WEBAUTHN", "NOT_ATTEMPTED", List.of());
        s.accept("OUTCOME", "CLEANUP_WEBAUTHN", "ROLLED_BACK", List.of());
        assertEquals(1, s.finish(1)); assertTrue(end().contains("result=PARTIAL db_result=PARTIAL"));
        s = state("enable", "alice"); s.accept("BEGIN", "ENABLE", "NOT_ATTEMPTED", List.of());
        s.accept("OUTCOME", "ENABLE", "UNKNOWN", List.of()); s.finish(1);
        assertTrue(end().contains("result=UNKNOWN db_result=UNKNOWN"));
    }
    @Test void logFailureAfterCommitIs74AndBeforeCommitIs73() throws Exception {
        var s = state("enable", "alice"); commit(s, 1);
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        assertEquals(74, s.finish(0));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----"));
        s = state("enable", "alice");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        assertEquals(73, s.finish(1));
    }
    @Test void plansCannotCommitAndHaveNoSecrets() throws Exception {
        var s = state("webauthn-reset", "alice");
        assertThrows(java.io.IOException.class, () -> s.accept("BEGIN", "RESET_WEBAUTHN", "NOT_ATTEMPTED", List.of()));
        assertEquals(0, s.finish(0)); assertTrue(end().contains("result=PLANNED db_result=NOT_ATTEMPTED"));
        assertFalse(Files.readString(log).contains("secret"));
    }
    @Test void missingCommitReceiptIsUnknownAndUrlPasswordsDoNotAffectTargetIdentity() throws Exception {
        Path conf = Files.createDirectories(home.resolve("conf/graphicalmatrix"));
        Path db = conf.resolve("db.properties");
        Files.writeString(db, "graphicalmatrix.db.url=jdbc:postgresql://user:SECRET_ONE@localhost:5432/db?password=SECOND_SECRET\n");
        var s = state("enable", "alice"); final String target = s.base.get("db_target_id");
        s.accept("BEGIN", "ENABLE", "NOT_ATTEMPTED", List.of()); s.finish(9);
        assertTrue(end().contains("result=UNKNOWN db_result=UNKNOWN"));
        Files.writeString(db, "graphicalmatrix.db.url=jdbc:postgresql://user:CHANGED@localhost:5432/db?password=OTHER\n");
        s = state("enable", "alice"); assertEquals(target, s.base.get("db_target_id")); s.finish(1);
        assertFalse(Files.readString(log).contains("SECRET"));
    }
    @Test void knownCommitSurvivesCloseFailureAndCompletedOutcomeIsNotOverwritten() throws Exception {
        var s = state("enable", "alice"); commit(s, 1);
        assertEquals(1, s.finish(1));
        assertTrue(end().contains("result=FAILED db_result=COMMITTED"));
        final long lines = Files.readAllLines(log).size();
        assertEquals(1, s.finish(73)); assertEquals(lines, Files.readAllLines(log).size());
    }
    @Test void crossUidPeerValidationDoesNotRequireReadingRootExecutable() {
        assertTrue(GraphicalMatrixAdminAuditClient.parentIdentityMatches("", "root", "root", "root"));
        assertTrue(GraphicalMatrixAdminAuditClient.parentIdentityMatches("", "", "root", "root"));
        assertTrue(GraphicalMatrixAdminAuditClient.parentIdentityMatches("/jdk/bin/java", "operator", "operator", "operator"));
        assertFalse(GraphicalMatrixAdminAuditClient.parentIdentityMatches("/bin/bash", "root", "root", "root"));
        assertFalse(GraphicalMatrixAdminAuditClient.parentIdentityMatches("", "root", "root", "attacker"));
        assertFalse(GraphicalMatrixAdminAuditClient.parentIdentityMatches("", "other", "root", "root"));
    }
}
