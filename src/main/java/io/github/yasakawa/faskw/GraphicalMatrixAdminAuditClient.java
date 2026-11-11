/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;

/** Capability-protected local IPC: the CLI parent alone opens the audit sink, including with H2 sudo workers. */
final class GraphicalMatrixAdminAuditClient {
    static final String SOCKET = "GRAPHICALMATRIX_ADMIN_AUDIT_SOCKET";
    static final String TOKEN = "GRAPHICALMATRIX_ADMIN_AUDIT_TOKEN";
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "graphicalmatrix-audit-io-timeout");
            thread.setDaemon(true);
            return thread;
        });
    private GraphicalMatrixAdminAuditClient() { }

    static boolean active() { return System.getenv(SOCKET) != null && !System.getenv(SOCKET).isBlank(); }

    static void send(final String type, final String stage, final String dbResult,
            final List<GraphicalMatrixCliOperationReport> reports) throws IOException {
        if (!active()) { return; } // Direct Java entry points are outside the shell CLI audit boundary.
        final byte[] payload = payload(type, stage, dbResult, reports);
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            final var deadline = DEADLINES.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, 20, java.util.concurrent.TimeUnit.SECONDS);
            try {
                socket.connect(UnixDomainSocketAddress.of(System.getenv(SOCKET)));
                final DataOutputStream out = new DataOutputStream(Channels.newOutputStream(socket));
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
                final DataInputStream in = new DataInputStream(Channels.newInputStream(socket));
                if (!"OK".equals(in.readUTF())) { throw new IOException("AUDIT_PARENT_REJECTED_REPORT"); }
                final long parentPid = in.readLong();
                final var peer = socket.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED);
                final Path directory = Path.of(System.getenv(SOCKET)).getParent();
                final boolean privateDirectory = Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(directory)
                    && !Files.isSymbolicLink(Path.of(System.getenv(SOCKET)))
                    && (((Number) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 0022) == 0;
                final String directoryOwner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).getName();
                boolean trustedParent = false;
                for (Optional<ProcessHandle> p = ProcessHandle.current().parent(); p.isPresent(); p = p.get().parent()) {
                    if (p.get().pid() == parentPid) {
                        final var info = p.get().info();
                        final String command = info.command().orElse("");
                        trustedParent = privateDirectory && parentIdentityMatches(command, info.user().orElse(""),
                            peer.user().getName(), directoryOwner);
                        break;
                    }
                }
                if (!trustedParent) { throw new IOException("AUDIT_PARENT_IDENTITY_MISMATCH"); }
            } finally { deadline.cancel(false); }
        }
    }

    static boolean parentIdentityMatches(final String command, final String processUser,
            final String peerUser, final String directoryOwner) {
        // Cross-UID workers may not read /proc/PID/exe. Peer credentials and the private directory
        // still authenticate its owner; require the real ancestor PID, and check Java when visible.
        return directoryOwner.equals(peerUser) && (processUser.isEmpty() || processUser.equals(peerUser))
            && (command.isEmpty() || command.endsWith("/java") || command.endsWith("/java.exe"));
    }

    static byte[] payload(final String type, final String stage, final String dbResult,
            final List<GraphicalMatrixCliOperationReport> reports) throws IOException {
        if (!stage.matches("[A-Z_0-9]{1,64}")) { throw new IOException("INVALID_AUDIT_STAGE"); }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long boundedSize = 0;
        if (reports.size() > 100000) { throw new IOException("AUDIT_BATCH_LIMIT"); }
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(Objects.toString(System.getenv(TOKEN), ""));
            out.writeUTF(type); out.writeUTF(stage); out.writeUTF(dbResult);
            out.writeInt(reports.size());
            for (var report : reports) {
                boundedSize += GraphicalMatrixAdminAuditWriter.encode(report.fields()).length + 2048;
                if (boundedSize > GraphicalMatrixAdminAuditWriter.MAX_BATCH) { throw new IOException("AUDIT_BATCH_LIMIT"); }
                out.writeUTF(report.targetUser()); out.writeLong(report.matchedRows()); out.writeLong(report.changedRows());
                out.writeInt(report.changes().size());
                for (var entry : report.changes().entrySet()) {
                    out.writeUTF(entry.getKey().name()); out.writeUTF(entry.getValue());
                }
                if (bytes.size() > GraphicalMatrixAdminAuditWriter.MAX_BATCH) { throw new IOException("AUDIT_BATCH_LIMIT"); }
            }
        }
        if (bytes.size() > GraphicalMatrixAdminAuditWriter.MAX_BATCH) { throw new IOException("AUDIT_BATCH_LIMIT"); }
        return bytes.toByteArray();
    }

    static Transaction transaction(final Connection c, final String stage) throws IOException {
        send("BEGIN", stage, "NOT_ATTEMPTED", List.of());
        return new Transaction(c, stage);
    }

    /** A commit acknowledgement is distinct from statement execution and from later connection close. */
    static final class Transaction implements AutoCloseable {
        private final Connection connection;
        private final String stage;
        private boolean commitAttempted;
        private boolean committed;
        private boolean finished;

        Transaction(final Connection c, final String stage) { connection = c; this.stage = stage; }

        void commit(final List<GraphicalMatrixCliOperationReport> reports) throws Exception {
            // Bound and validate all safe metadata before the database can be committed.
            payload("BATCH", stage, "COMMITTED", reports);
            commitAttempted = true;
            connection.commit();
            committed = true;
            finished = true;
            try { send("BATCH", stage, "COMMITTED", reports); }
            catch (IOException ex) { throw new AuditAfterCommitException(); }
        }

        @Override public void close() throws Exception {
            if (finished) { return; }
            finished = true;
            String result = "ROLLED_BACK";
            try { connection.rollback(); } catch (Exception ex) { result = "UNKNOWN"; }
            if (commitAttempted || stage.equals("SCHEMA_INIT")) { result = "UNKNOWN"; }
            try { send("OUTCOME", stage, result, List.of()); }
            catch (IOException ex) {
                if (committed) { throw new AuditAfterCommitException(); }
                throw ex;
            }
        }
    }

    static final class AuditAfterCommitException extends IOException {
        AuditAfterCommitException() { super("DB_COMMITTED_AUDIT_INCOMPLETE: do not rerun blindly"); }
    }
}
