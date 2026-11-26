package io.github.yasakawa.faskw;

import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.*;

/** Test-only launcher: production has no ownership override or audit-disable environment switch. */
public final class GraphicalMatrixAdminAuditFixture {
    public static void main(final String[] args) throws Exception {
        final Path home = Path.of(args[0]);
        Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        for (String name : List.of("audit.log", "audit.lock")) {
            final Path path = home.resolve(name);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(path);
                Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----"));
            }
        }
        final Properties p = new Properties();
        p.setProperty("graphicalmatrix.admin.audit.logFile", home.resolve("audit.log").toString());
        p.setProperty("graphicalmatrix.admin.audit.lockFile", home.resolve("audit.lock").toString());
        final var writer = new GraphicalMatrixAdminAuditWriter(p,
            ((Number) Files.getAttribute(home, "unix:uid")).longValue(),
            Files.readAttributes(home, PosixFileAttributes.class).group().getName());
        int code;
        try { code = GraphicalMatrixAdminAuditTool.run(home, args[1], GraphicalMatrixAdminAuditTool.readArguments(), writer); }
        catch (Exception ex) { ex.printStackTrace(); System.err.println("AUDIT_PREFLIGHT_FAILED"); code = 73; }
        System.exit(code);
    }
}
