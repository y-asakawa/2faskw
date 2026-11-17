package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixAdminAuditCliTest {
    @TempDir Path home;
    Path bin, log;
    String cp, java, url;
    final Map<String, String> extraEnvironment = new HashMap<>();
    @BeforeEach void setup() throws Exception {
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        bin = Files.createDirectory(home.resolve("bin"));
        Path conf = Files.createDirectories(home.resolve("conf/graphicalmatrix"));
        Files.writeString(conf.resolve("graphicalmatrix.properties"), "graphicalmatrix.sequence.storage=plaintext\n"
            + "graphicalmatrix.graphicals=img01-25\ngraphicalmatrix.choice=4\ngraphicalmatrix.order=1\n"
            + "graphicalmatrix.aliases=A:img01,B:img02,C:img03,D:img04,E:img05,F:img06,G:img07,H:img08\n");
        url = "jdbc:h2:file:" + home.resolve("testdb") + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false";
        Files.writeString(conf.resolve("db.properties"), "graphicalmatrix.db.driver=org.h2.Driver\n"
            + "graphicalmatrix.db.url=" + url + "\ngraphicalmatrix.db.user=sa\ngraphicalmatrix.db.autoInit=false\n");
        try (var c = DriverManager.getConnection(url, "sa", "")) {
            GraphicalMatrixSequenceMigrationTool.initSchema(c);
            c.createStatement().execute("INSERT INTO graphicalmatrix_enrollment(user_id,sequence,initial_sequence,created_at,updated_at,totp_seed,totp_status)"
                + " VALUES ('alice','img05,img06,img07,img08','A,B,C,D',1,1,'JBSWY3DPEHPK3PXP','ACTIVE')");
        }
        log = Files.createFile(home.resolve("audit.log"));
        Files.createFile(home.resolve("audit.lock"));
        for (Path file : List.of(log, home.resolve("audit.lock"))) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
        }
        cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        java = Path.of(System.getProperty("java.home"), "bin/java").toString();
        script("java", "if [[ \"${3:-}\" == io.github.yasakawa.faskw.GraphicalMatrixAdminAuditTool && \"${4:-}\" == run ]]; then\n"
            + " printf '%s\\n' \"$@\" > \"$GRAPHICALMATRIX_HOME/supervisor.argv\"\n"
            + " shift 4\n exec '" + java + "' -cp '" + cp + "' io.github.yasakawa.faskw.GraphicalMatrixAdminAuditFixture \"$@\"\nfi\n"
            + "if [[ \"${3:-}\" == io.github.yasakawa.faskw.GraphicalMatrixH2Command && \"${AUDIT_TEST_DISPLAY_FAIL:-}\" == 1 ]]; then exit 1; fi\n"
            + "exec '" + java + "' \"$@\"\n");
        script("id", "if [[ \"${1:-}\" == -un ]]; then echo jetty; else exec /usr/bin/id \"$@\"; fi\n");
    }
    void script(String name, String body) throws Exception {
        Path path = bin.resolve(name); Files.writeString(path, "#!/bin/bash\nset -euo pipefail\n" + body);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    int cli(String input, boolean displayFail, String... args) throws Exception {
        final List<String> command = new ArrayList<>(List.of("/bin/bash", Path.of("graphicalmatrix-db.sh").toAbsolutePath().toString()));
        command.addAll(Arrays.asList(args));
        final var pb = new ProcessBuilder(command).redirectOutput(home.resolve("stdout").toFile()).redirectError(home.resolve("stderr").toFile());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("GRAPHICALMATRIX_HOME", home.toString());
        pb.environment().put("SEQUENCE_TOOL_CP", cp);
        pb.environment().put("GRAPHICALMATRIX_TIME_ZONE", "UTC");
        pb.environment().put("H2_JAR", Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        pb.environment().put("AUDIT_TEST_DISPLAY_FAIL", displayFail ? "1" : "0");
        pb.environment().put("SUDO_USER", "unverified-claim");
        pb.environment().putAll(extraEnvironment);
        final Process child = pb.start();
        try (var out = child.getOutputStream()) { out.write(input.getBytes(StandardCharsets.UTF_8)); }
        if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("CLI timeout"); }
        return child.exitValue();
    }
    String value(String column, String user) throws Exception {
        try (var c = DriverManager.getConnection(url, "sa", ""); var ps = c.prepareStatement("SELECT " + column + " FROM graphicalmatrix_enrollment WHERE user_id=?")) {
            ps.setString(1, user); try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
    String diagnostics() throws Exception { return Files.readString(home.resolve("stderr")) + Files.readString(home.resolve("stdout")); }

    @Test void resetAndCancelKeepOriginalInteractionAndRecordRealIdentity() throws Exception {
        assertEquals(0, cli("NO\n", false, "alice", "RESET"), diagnostics());
        assertEquals("0", value("state_version", "alice"));
        assertTrue(Files.readString(log).contains("result=CANCELLED"));
        assertEquals(0, cli("", false, "alice", "RESET", "-y"), diagnostics());
        assertEquals("1", value("state_version", "alice")); assertNull(value("totp_seed", "alice"));
        final String audit = Files.readString(log);
        assertTrue(audit.contains("result=SUCCESS db_result=COMMITTED"));
        assertTrue(audit.contains("target_user=alice"));
        assertTrue(audit.contains("invoker_claim=unverified-claim invoker_source=unverified_environment"));
        assertFalse(audit.contains("effective_user=jetty"));
        assertFalse(audit.contains("A,B,C,D")); assertFalse(audit.contains("img01,img02"));
        assertFalse(audit.contains("JBSWY3DPEHPK3PXP"));
    }
    @Test void preflightFailureStopsBeforeMutationAndDisplayFailureRetainsCommit() throws Exception {
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        assertEquals(73, cli("", false, "disable", "alice"), diagnostics());
        assertEquals("ACTIVE", value("status", "alice"));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----"));
        assertEquals(1, cli("", true, "disable", "alice"), diagnostics());
        assertEquals("DISABLED", value("status", "alice"));
        assertTrue(Files.readAllLines(log).getLast().contains("result=FAILED db_result=COMMITTED"));
    }
    @Test void addSetMethodNoMatchAndPhysicalDeleteUseExistingCommands() throws Exception {
        assertEquals(0, cli("", false, "add", "bob", "A", "B", "C", "D"), diagnostics());
        assertFalse(Files.readString(home.resolve("supervisor.argv")).contains("bob"));
        assertFalse(Files.readAllLines(home.resolve("supervisor.argv")).contains("A"));
        assertEquals("A,B,C,D", value("initial_sequence", "bob"));
        assertEquals(0, cli("", false, "set-method", "bob", "TOTP"), diagnostics());
        assertEquals("TOTP", value("mfa_method", "bob"));
        assertEquals(0, cli("", false, "enable", "nobody"), diagnostics());
        assertTrue(Files.readAllLines(log).getLast().contains("result=NO_MATCH"));
        assertEquals(0, cli("", false, "delete", "bob"), diagnostics());
        assertNull(value("status", "bob"));
        assertTrue(Files.readString(log).contains("stage=DISABLE_BEFORE_DELETE"));
        assertTrue(Files.readString(log).contains("stage=DELETE_ENROLLMENT"));
    }
    @Test void csvPlanApplyAndRollbackKeepSecretValuesOutOfAudit() throws Exception {
        Path csv = home.resolve("users.csv");
        Files.writeString(csv, "action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence\nA,bob,GraphicalMatrix,on,\"A,B,C,D\",\"A,B,C,D\"\n");
        assertEquals(0, cli("", false, "csv", csv.toString()), diagnostics());
        assertNull(value("status", "bob")); assertTrue(Files.readString(log).contains("result=PLANNED"));
        assertEquals(0, cli("", false, "csv", csv.toString(), "--apply"), diagnostics());
        assertEquals("ACTIVE", value("status", "bob"));
        assertTrue(Files.readString(log).contains("csv_sha256:"));
        final int before = Files.readAllLines(log).size();
        Files.writeString(csv, "action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence\nD,bob,,,,\nD,nobody,,,,\n");
        assertEquals(1, cli("", false, "provisioning-csv", csv.toString(), "--apply"), diagnostics());
        assertEquals("ACTIVE", value("status", "bob"));
        final String tail = String.join("\n", Files.readAllLines(log).subList(before, Files.readAllLines(log).size()));
        assertFalse(tail.contains("result=SUCCESS")); assertTrue(tail.contains("db_result=ROLLED_BACK"));
        assertTrue(Files.readAllLines(log).getLast().contains("csv_sha256:"));
        assertFalse(Files.readString(log).contains("A,B,C,D"));
    }
    @Test void autoInitReadHasIndependentSchemaOperationWithoutEnrollmentSuccess() throws Exception {
        Path properties = home.resolve("conf/graphicalmatrix/db.properties");
        Files.writeString(properties, Files.readString(properties).replace("autoInit=false", "autoInit=true"));
        assertEquals(0, cli("", false, "show", "alice"), diagnostics());
        final String audit = Files.readString(log);
        assertTrue(audit.contains("action=SCHEMA_INIT"));
        assertTrue(audit.contains("action=READ"));
        assertEquals("0", value("state_version", "alice"));
    }
    @Test void forgedParentEnvironmentDoesNotBypassMandatoryAudit() throws Exception {
        extraEnvironment.put(GraphicalMatrixAdminAuditClient.SOCKET, home.resolve("not-a-server").toString());
        extraEnvironment.put(GraphicalMatrixAdminAuditClient.TOKEN, "forged");
        assertEquals(73, cli("", false, "disable", "alice"), diagnostics());
        assertEquals("ACTIVE", value("status", "alice"));
    }
}
