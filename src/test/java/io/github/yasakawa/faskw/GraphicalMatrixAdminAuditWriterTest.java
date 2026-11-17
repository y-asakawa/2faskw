package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixAdminAuditWriterTest {
    @TempDir Path home;
    Path log, lock;
    Properties properties;
    GraphicalMatrixAdminAuditWriter writer;

    @BeforeEach void prepare() throws Exception {
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        log = Files.createFile(home.resolve("audit.log")); lock = Files.createFile(home.resolve("audit.lock"));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----"));
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-rw----"));
        properties = new Properties();
        properties.setProperty("graphicalmatrix.admin.audit.logFile", log.toString());
        properties.setProperty("graphicalmatrix.admin.audit.lockFile", lock.toString());
        properties.setProperty("graphicalmatrix.admin.audit.lockTimeoutMillis", "100");
        writer = writer();
    }

    GraphicalMatrixAdminAuditWriter writer() throws Exception {
        return new GraphicalMatrixAdminAuditWriter(properties, ((Number) Files.getAttribute(home, "unix:uid")).longValue(),
            Files.readAttributes(home, PosixFileAttributes.class).group().getName());
    }

    @Test void encodesNewlinesAndDelimitersWithoutCreatingExtraRecords() throws Exception {
        writer.append(List.of(Map.of("event", "ADMIN_CLI_START", "invoker_claim", "x\ny =%\t日本語")));
        final String line = Files.readString(log);
        assertEquals(1, line.lines().count());
        assertTrue(line.contains("invoker_claim=x%0Ay%20%3D%25%09"));
        assertFalse(line.contains("日本語"));
        assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("password", "secret"))));
    }

    @Test void rejectsLinksAndUnsafePermissions() throws Exception {
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("event", "END"))));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----"));
        Files.createLink(home.resolve("hard-link"), log);
        assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("event", "END"))));
        Files.delete(home.resolve("hard-link"));
        Files.move(log, home.resolve("actual")); Files.createSymbolicLink(log, home.resolve("actual"));
        assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("event", "END"))));
        Files.delete(log); Files.move(home.resolve("actual"), log);
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("event", "END"))));
    }

    @Test void hasBoundedLockWaitAndRecordSize() throws Exception {
        try (var channel = java.nio.channels.FileChannel.open(lock, StandardOpenOption.WRITE); var held = channel.lock()) {
            long start = System.nanoTime();
            assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("event", "END"))));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
        }
        assertThrows(java.io.IOException.class, () -> writer.append(List.of(Map.of("changes", "x".repeat(9000)))));
        assertEquals(0, Files.size(log));
    }

    @Test void parallelAppendsDoNotInterleaveAndRotationReopens() throws Exception {
        properties.setProperty("graphicalmatrix.admin.audit.lockTimeoutMillis", "3000"); writer = writer();
        try (var workers = Executors.newFixedThreadPool(8)) {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final int n = i;
                futures.add(workers.submit(() -> { writer.append(List.of(Map.of("event", "CHANGE", "seq", "" + n))); return null; }));
            }
            for (var future : futures) { future.get(); }
        }
        assertEquals(32, Files.readAllLines(log).size());
        final Path old = home.resolve("audit.log.1"); Files.move(log, old);
        final long inode = ((Number) Files.getAttribute(lock, "unix:ino")).longValue();
        Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw----")));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----"));
        writer.append(List.of(Map.of("event", "AFTER_ROTATION")));
        assertEquals(1, Files.readAllLines(log).size());
        assertEquals(32, Files.readAllLines(old).size());
        assertEquals(inode, ((Number) Files.getAttribute(lock, "unix:ino")).longValue());
    }

    @Test void writesTenThousandRecordsInOneDurableBatch() throws Exception {
        final List<Map<String, String>> records = new ArrayList<>();
        for (int i = 0; i < 10000; i++) { records.add(Map.of("event", "ADMIN_CLI_CHANGE", "target_user", "user-" + i)); }
        writer.append(records);
        assertEquals(10000, Files.readAllLines(log).size());
    }

    @Test void typedChangesRejectSecretsAndInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> GraphicalMatrixCliOperationReport.changed("user", 1,
            Map.of(GraphicalMatrixCliOperationReport.Field.MFA_METHOD, "JBSWY3DPEHPK3PXP")));
        assertThrows(IllegalArgumentException.class, () -> GraphicalMatrixCliOperationReport.changed("user\nforged", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> GraphicalMatrixCliOperationReport.changed("user", -1, Map.of()));
    }
}
