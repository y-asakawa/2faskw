/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Runs the unchanged interactive CLI under a durable audit parent; never records argv or child output. */
public final class GraphicalMatrixAdminAuditTool {
    private GraphicalMatrixAdminAuditTool() { }

    public static void main(final String[] args) {
        int code;
        try {
            code = switch (args[0]) {
                case "run" -> run(Path.of(args[1]), args[2], readArguments());
                case "verify" -> { GraphicalMatrixAdminAuditClient.send("HELLO", "VALIDATION", "NOT_ATTEMPTED", List.of());
                    if (!GraphicalMatrixAdminAuditClient.active()) { throw new IOException(); } yield 0; }
                case "mark" -> { GraphicalMatrixAdminAuditClient.send("MARK", args[1], "NOT_ATTEMPTED", List.of()); yield 0; }
                case "credential-sha256" -> {
                    final byte[] bytes = System.in.readNBytes(16385);
                    if (bytes.length > 16384) { throw new IOException(); }
                    System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); yield 0;
                }
                case "file-sha256" -> {
                    final var digest = MessageDigest.getInstance("SHA-256");
                    try (var input = Files.newInputStream(Path.of(args[1]))) {
                        final byte[] bytes = new byte[65536];
                        for (int n; (n = input.read(bytes)) >= 0;) { digest.update(bytes, 0, n); }
                    }
                    final String sha256 = HexFormat.of().formatHex(digest.digest());
                    GraphicalMatrixAdminAuditClient.send("CONTEXT", "SNAPSHOT", "NOT_ATTEMPTED",
                        List.of(GraphicalMatrixCliOperationReport.changed("*", 0,
                            Map.of(GraphicalMatrixCliOperationReport.Field.CSV_SHA256, sha256))));
                    System.out.println(sha256); yield 0;
                }
                case "csv-plan" -> {
                    final List<GraphicalMatrixCliOperationReport> reports = new ArrayList<>();
                    try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                        long size = 0;
                        for (String line; (line = input.readLine()) != null;) {
                            if (line.length() > 2048) { throw new IOException(); }
                            final String[] fields = line.split("\t", -1);
                            if (fields.length != 5) { throw new IOException(); }
                            final var changes = new EnumMap<GraphicalMatrixCliOperationReport.Field, String>(GraphicalMatrixCliOperationReport.Field.class);
                            changes.put(GraphicalMatrixCliOperationReport.Field.CSV_SHA256, args[1]);
                            changes.put(GraphicalMatrixCliOperationReport.Field.CSV_ACTION, fields[1]);
                            changes.put(GraphicalMatrixCliOperationReport.Field.CSV_LINE, fields[4]);
                            if (!fields[1].equals("D")) {
                                changes.put(GraphicalMatrixCliOperationReport.Field.MFA_METHOD, fields[2]);
                                changes.put(GraphicalMatrixCliOperationReport.Field.FORCE_SEQUENCE_CHANGE, fields[3]);
                            }
                            var report = GraphicalMatrixCliOperationReport.changed(fields[0], 0, changes);
                            size += GraphicalMatrixAdminAuditWriter.encode(report.fields()).length + 1024;
                            if (size > GraphicalMatrixAdminAuditWriter.MAX_BATCH) { throw new IOException(); }
                            reports.add(report);
                        }
                    }
                    GraphicalMatrixAdminAuditClient.send("PLAN", "IMPORT_CSV", "NOT_ATTEMPTED", reports); yield 0;
                }
                default -> 73;
            };
        } catch (Exception ex) {
            System.err.println("ERROR: CLI audit unavailable; no new update may start. Prepare graphicalmatrix-admin-audit first.");
            code = 73;
        }
        System.exit(code);
    }

    static String action(final String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("RESET")) { return "RESET"; }
        return switch (args.length == 0 ? "" : args[0]) {
            case "add", "set-sequence" -> "SET_SEQUENCE";
            case "set-initial-sequence" -> "SET_INITIAL_SEQUENCE";
            case "reset-user" -> "RESET";
            case "set-method" -> "SET_METHOD";
            case "require-change" -> "SET_REQUIRE_CHANGE";
            case "enable" -> "ENABLE";
            case "disable" -> "DISABLE";
            case "unlock", "reset-failures" -> "UNLOCK";
            case "lock" -> "LOCK";
            case "set-totp-seed" -> "SET_TOTP_SEED";
            case "clear-totp-seed", "reset-totp" -> "RESET_TOTP";
            case "webauthn-reset" -> "RESET_WEBAUTHN";
            case "webauthn-delete" -> "DELETE_WEBAUTHN_CREDENTIAL";
            case "delete" -> "DELETE_USER";
            case "csv", "provisioning-csv" -> "IMPORT_CSV";
            case "migrate-sequence-storage" -> "MIGRATE_SEQUENCE_STORAGE";
            case "migrate-totp-seed-storage" -> "MIGRATE_TOTP_STORAGE";
            case "invalidate-pending-totp" -> "INVALIDATE_PENDING_TOTP";
            case "csv-export", "export-csv" -> "EXPORT_CSV";
            case "list", "show", "security-status", "webauthn-list", "config-help", "sequence-mode", "totp-seed-mode", "help", "--help", "-h", "" -> "READ";
            default -> "UNKNOWN";
        };
    }

    static String[] readArguments() throws IOException {
        // Keep the original CLI arguments out of the supervisor JVM argv; leave interactive stdin untouched.
        try (var input = Files.newInputStream(Path.of("/dev/fd/3"))) {
            final ByteArrayOutputStream received = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            // readNBytes on a NIO file stream may seek; this descriptor is a pipe.
            for (int n; (n = input.read(buffer)) >= 0;) {
                if (received.size() + n > 1024 * 1024) { throw new IOException("CLI_ARGUMENT_LIMIT"); }
                received.write(buffer, 0, n);
            }
            final byte[] bytes = received.toByteArray();
            if (bytes.length == 0 || bytes[bytes.length - 1] != 0) { throw new IOException("INVALID_CLI_ARGUMENT_CHANNEL"); }
            final String[] fields = new String(bytes, StandardCharsets.UTF_8).split("\u0000", -1);
            return Arrays.copyOf(fields, fields.length - 1);
        }
    }

    static String mode(final String action, final String[] args) {
        if (action.equals("READ")) { return "READ"; }
        if (action.equals("EXPORT_CSV")) { return "EXPORT"; }
        if (Set.of("RESET_WEBAUTHN", "DELETE_WEBAUTHN_CREDENTIAL", "IMPORT_CSV",
                "MIGRATE_SEQUENCE_STORAGE", "MIGRATE_TOTP_STORAGE", "INVALIDATE_PENDING_TOTP").contains(action)) {
            final int positional = Set.of("IMPORT_CSV", "RESET_WEBAUTHN", "DELETE_WEBAUTHN_CREDENTIAL").contains(action) ? 2 : 1;
            final List<String> options = Arrays.asList(args).subList(Math.min(positional, args.length), args.length);
            final boolean apply = options.contains("--apply") || (action.equals("IMPORT_CSV")
                ? options.stream().anyMatch(Set.of("--yes", "-y", "YES")::contains) : options.contains("apply"));
            return apply ? "APPLY" : "PLAN";
        }
        return "APPLY";
    }

    private static int run(final Path home, final String script, final String[] args) throws Exception {
        final GraphicalMatrixAdminAuditWriter writer = new GraphicalMatrixAdminAuditWriter(home);
        return run(home, script, args, writer);
    }

    static int run(final Path home, final String script, final String[] args,
            final GraphicalMatrixAdminAuditWriter writer) throws Exception {
        final State state = new State(home, args, writer);
        state.write("ADMIN_CLI_START", "STARTED", "NOT_ATTEMPTED", Map.of());
        final Path directory = Files.createTempDirectory(Path.of("/tmp"), "gm-audit-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x")));
        final Path socketPath = directory.resolve("s");
        final byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        final String token = HexFormat.of().formatHex(random);
        final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
        final ExecutorService requests = Executors.newSingleThreadExecutor();
        Process child = null;
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-rw-rw-"));
            requests.submit(() -> {
                while (server.isOpen()) {
                    try (SocketChannel peer = server.accept()) {
                        final var deadline = deadlines.schedule(() -> { try { peer.close(); } catch (IOException ignored) { } }, 15, TimeUnit.SECONDS);
                        try { receive(peer, token, state); }
                        finally { deadline.cancel(false); }
                    } catch (Exception ex) {
                        if (!server.isOpen()) { break; }
                    }
                }
            });
            final List<String> command = new ArrayList<>(List.of("/bin/bash", script));
            command.addAll(Arrays.asList(args));
            final ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
            pb.environment().put(GraphicalMatrixAdminAuditClient.SOCKET, socketPath.toString());
            pb.environment().put(GraphicalMatrixAdminAuditClient.TOKEN, token);
            child = pb.start();
            final Process running = child;
            final Thread shutdown = new Thread(() -> {
                running.descendants().forEach(ProcessHandle::destroy);
                running.destroy();
                try { running.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                if (running.isAlive()) { running.destroyForcibly(); }
                state.finish(143);
            });
            Runtime.getRuntime().addShutdownHook(shutdown);
            final int operationCode;
            try { operationCode = child.waitFor(); }
            finally { Runtime.getRuntime().removeShutdownHook(shutdown); }
            return state.finish(operationCode);
        } catch (Exception ex) {
            // A server/close failure after a receipt must not become a pre-write exit 73.
            if (state.finished) { return state.finalCode; }
            if (child != null && child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroy);
                child.destroyForcibly();
                child.waitFor(3, TimeUnit.SECONDS);
            }
            state.auditFailed = true;
            return state.finish(1);
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); }
            requests.shutdownNow(); deadlines.shutdownNow();
            try { Files.deleteIfExists(socketPath); Files.deleteIfExists(directory); }
            catch (IOException ex) { System.err.println("WARNING: audit IPC cleanup incomplete; recorded DB outcome retained"); }
        }
    }

    private static void receive(final SocketChannel peer, final String token, final State state) throws Exception {
        final DataInputStream input = new DataInputStream(Channels.newInputStream(peer));
        final int length = input.readInt();
        if (length < 0 || length > GraphicalMatrixAdminAuditWriter.MAX_BATCH) { return; }
        // Authenticate before allocating the possibly large report batch.
        if (!MessageDigest.isEqual(input.readUTF().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) { return; }
        final String type = input.readUTF(), stage = input.readUTF(), dbResult = input.readUTF();
        if (!Set.of("HELLO", "BEGIN", "BATCH", "OUTCOME", "MARK", "PLAN", "READ_RESULT", "CONTEXT").contains(type)
                || !stage.matches("[A-Z_0-9]{1,64}")
                || !Set.of("NOT_ATTEMPTED", "COMMITTED", "ROLLED_BACK", "UNKNOWN").contains(dbResult)) { return; }
        final int count = input.readInt();
        if (count < 0 || count > 100000 || (count > 0 && !Set.of("BATCH", "PLAN", "READ_RESULT", "CONTEXT").contains(type))) { return; }
        final List<GraphicalMatrixCliOperationReport> reports = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < count; i++) {
            final String target = input.readUTF();
            final long matched = input.readLong(), changed = input.readLong();
            final int fields = input.readInt();
            if (fields < 0 || fields > GraphicalMatrixCliOperationReport.Field.values().length) { return; }
            final Map<GraphicalMatrixCliOperationReport.Field, String> changes = new EnumMap<>(GraphicalMatrixCliOperationReport.Field.class);
            for (int j = 0; j < fields; j++) {
                if (changes.put(GraphicalMatrixCliOperationReport.Field.valueOf(input.readUTF()), input.readUTF()) != null) { return; }
            }
            var report = new GraphicalMatrixCliOperationReport(target, matched, changed, changes);
            bytes += GraphicalMatrixAdminAuditWriter.encode(report.fields()).length + 1024;
            if (bytes > GraphicalMatrixAdminAuditWriter.MAX_BATCH) { return; }
            reports.add(report);
        }
        String response = "OK";
        try { state.accept(type, stage, dbResult, reports); }
        catch (IOException ex) { state.auditFailed = true; response = "FAIL"; }
        final DataOutputStream output = new DataOutputStream(Channels.newOutputStream(peer));
        output.writeUTF(response); output.writeLong(ProcessHandle.current().pid()); output.flush();
    }

    static final class State {
        final GraphicalMatrixAdminAuditWriter writer;
        final Map<String, String> base = new LinkedHashMap<>();
        final long started = System.nanoTime();
        long sequence, matched, changed;
        boolean committed, actionCommitted, attempted, rolledBack, uncertain, cancelled, finished;
        String schemaOperation;
        String lastCommittedStage = "NONE";
        volatile boolean auditFailed;
        String stage = "VALIDATION";
        final String mode;
        int finalCode;

        State(final Path home, final String[] args, final GraphicalMatrixAdminAuditWriter writer) throws Exception {
            this.writer = writer;
            final String action = action(args);
            mode = mode(action, args);
            base.put("op_id", UUID.randomUUID().toString());
            base.put("parent_op_id", "none");
            base.put("action", action); base.put("mode", mode);
            String target = args.length > 1 && args[1].equalsIgnoreCase("RESET") ? args[0] : args.length > 1 ? args[1] : "*";
            if (Set.of("READ", "UNKNOWN", "IMPORT_CSV", "EXPORT_CSV", "MIGRATE_SEQUENCE_STORAGE", "MIGRATE_TOTP_STORAGE", "INVALIDATE_PENDING_TOTP").contains(action)
                    || !target.matches("[A-Za-z0-9._@-]{1,255}")) { target = "*"; }
            base.put("target_user", target);
            final var unix = new com.sun.security.auth.module.UnixSystem();
            base.put("uid", Long.toString(unix.getUid()));
            base.put("euid", osIdentity("-u"));
            base.put("effective_user", osIdentity("-un"));
            base.put("login_uid", Files.isReadable(Path.of("/proc/self/loginuid"))
                ? Files.readString(Path.of("/proc/self/loginuid")).strip() : "unknown");
            String claim = System.getenv("SUDO_USER");
            base.put("invoker_claim", claim == null ? "none" : claim.substring(0, Math.min(255, claim.length())));
            base.put("invoker_source", claim == null ? "none" : "unverified_environment");
            String hostname;
            try { hostname = Files.isReadable(Path.of("/proc/sys/kernel/hostname"))
                ? Files.readString(Path.of("/proc/sys/kernel/hostname")).strip() : InetAddress.getLocalHost().getHostName(); }
            catch (Exception ex) { hostname = "unknown"; }
            base.put("host", hostname);
            base.put("deployment_id", digest(home.toRealPath().toString()));
            final Properties db = new Properties();
            final String override = System.getenv("DB_PROPERTIES");
            final Path dbPath = override == null ? home.resolve("conf/graphicalmatrix/db.properties") : Path.of(override);
            if (Files.isRegularFile(dbPath)) { try (var reader = Files.newBufferedReader(dbPath)) { db.load(reader); } }
            final String url = db.getProperty("graphicalmatrix.db.url", "jdbc:h2:default");
            base.put("db_type", url.startsWith("jdbc:postgresql:") ? "postgresql" : "h2");
            // Hash the whole identity; never emit URLs, userinfo, parameters or passwords.
            String databaseIdentity;
            if (url.startsWith("jdbc:postgresql:")) {
                try {
                    final URI uri = URI.create(url.substring(5));
                    databaseIdentity = "postgresql:" + Objects.toString(uri.getHost(), "unparsed-host")
                        + ":" + uri.getPort() + "/" + Objects.toString(uri.getPath(), "");
                } catch (IllegalArgumentException ex) { databaseIdentity = "postgresql:unparsed-target"; }
            } else { databaseIdentity = url.split("[;?]", 2)[0]; }
            base.put("db_target_id", digest(databaseIdentity));
        }

        synchronized void accept(final String type, final String nextStage, final String dbResult,
                final List<GraphicalMatrixCliOperationReport> reports) throws IOException {
            if (finished || auditFailed) { throw new IOException(); }
            stage = nextStage;
            if (type.equals("HELLO")) { return; }
            if (type.equals("MARK")) { cancelled |= stage.equals("CANCELLED"); return; }
            if (type.equals("CONTEXT")) {
                if (!base.get("action").equals("IMPORT_CSV") || reports.size() != 1
                        || !reports.getFirst().changes().keySet().equals(Set.of(GraphicalMatrixCliOperationReport.Field.CSV_SHA256))) {
                    throw new IOException("INVALID_AUDIT_CONTEXT");
                }
                base.put("changes", reports.getFirst().fields().get("changes"));
                write("ADMIN_CLI_CHANGE", "STARTED", "NOT_ATTEMPTED", Map.of());
                return;
            }
            if (type.equals("PLAN") || type.equals("READ_RESULT")) {
                if (type.equals("PLAN") && !mode.equals("PLAN") || type.equals("READ_RESULT") && !mode.equals("EXPORT")) { throw new IOException(); }
                final List<Map<String, String>> records = new ArrayList<>();
                for (var report : reports) {
                    records.add(record("ADMIN_CLI_CHANGE", type.equals("PLAN") ? "PLANNED" : "SUCCESS", "NOT_ATTEMPTED", report.fields()));
                }
                writer.append(records);
                return;
            }
            if (type.equals("BEGIN")) {
                if (mode.equals("PLAN") && !stage.equals("SCHEMA_INIT")) { throw new IOException("PLAN_WRITE_REJECTED"); }
                if (stage.equals("SCHEMA_INIT")) {
                    schemaOperation = UUID.randomUUID().toString();
                    write("ADMIN_CLI_START", "STARTED", "NOT_ATTEMPTED", Map.of("stage", "SCHEMA_INIT"));
                }
                attempted = true; uncertain = true;
                return;
            }
            if (dbResult.equals("COMMITTED")) {
                lastCommittedStage = stage;
                committed = true; actionCommitted |= !stage.equals("SCHEMA_INIT"); uncertain = false;
                final List<Map<String, String>> records = new ArrayList<>();
                for (var report : reports) {
                    matched += report.matchedRows(); changed += report.changedRows();
                    records.add(record("ADMIN_CLI_CHANGE", report.matchedRows() == 0 ? "NO_MATCH"
                        : report.changedRows() == 0 ? "NO_CHANGE" : "SUCCESS", dbResult, report.fields()));
                }
                if (records.isEmpty()) { records.add(record(stage.equals("SCHEMA_INIT") ? "ADMIN_CLI_END" : "ADMIN_CLI_CHANGE",
                    stage.equals("SCHEMA_INIT") ? "SUCCESS" : "NO_CHANGE", dbResult, Map.of())); }
                writer.append(records);
                if (stage.equals("SCHEMA_INIT")) { stage = "SCHEMA_READY"; }
            } else {
                rolledBack |= dbResult.equals("ROLLED_BACK"); uncertain = dbResult.equals("UNKNOWN");
                write(stage.equals("SCHEMA_INIT") ? "ADMIN_CLI_END" : "ADMIN_CLI_CHANGE", "FAILED", dbResult, Map.of());
                if (stage.equals("SCHEMA_INIT")) { stage = "SCHEMA_FAILED"; }
            }
        }

        synchronized Map<String, String> record(final String event, final String result, final String dbResult,
                final Map<String, String> fields) {
            final Map<String, String> r = new LinkedHashMap<>(base);
            r.put("seq", Long.toString(++sequence)); r.put("event", event); r.put("stage", stage);
            r.put("result", result); r.put("db_result", dbResult); r.putAll(fields);
            if (stage.equals("SCHEMA_INIT") && schemaOperation != null) {
                r.put("parent_op_id", base.get("op_id")); r.put("op_id", schemaOperation); r.put("action", "SCHEMA_INIT");
                r.put("mode", "APPLY");
            }
            return r;
        }

        synchronized void write(final String event, final String result, final String dbResult,
                final Map<String, String> fields) throws IOException { writer.append(List.of(record(event, result, dbResult, fields))); }

        synchronized int finish(final int operationCode) {
            if (finished) { return finalCode; }
            finished = true;
            if (stage.equals("SCHEMA_INIT")) { stage = "SCHEMA_INTERRUPTED"; }
            final boolean partial = committed && (rolledBack || operationCode != 0 && actionCommitted
                && (base.get("action").equals("DELETE_USER") && !lastCommittedStage.equals("DELETE_ENROLLMENT")
                    || base.get("action").equals("MIGRATE_SEQUENCE_STORAGE")));
            final String dbResult = uncertain ? "UNKNOWN" : committed ? (partial ? "PARTIAL" : "COMMITTED")
                : rolledBack ? "ROLLED_BACK" : "NOT_ATTEMPTED";
            final String result = uncertain ? "UNKNOWN" : cancelled ? "CANCELLED"
                : operationCode != 0 ? (partial ? "PARTIAL" : attempted ? "FAILED" : "REJECTED")
                : mode.equals("PLAN") ? "PLANNED" : mode.equals("EXPORT") ? "SUCCESS"
                : actionCommitted ? matched == 0 ? base.get("target_user").equals("*") ? "NO_CHANGE" : "NO_MATCH"
                    : changed == 0 ? "NO_CHANGE" : "SUCCESS" : "NO_CHANGE";
            finalCode = operationCode == 0 ? 0 : 1;
            if (auditFailed || operationCode == 74 && uncertain) { finalCode = committed || uncertain ? 74 : 73; }
            try {
                write("ADMIN_CLI_END", result, dbResult, Map.of("matched_rows", Long.toString(matched), "changed_rows", Long.toString(changed),
                    "exit_code", Integer.toString(finalCode), "operation_exit_code", Integer.toString(operationCode),
                    "duration_ms", Long.toString((System.nanoTime() - started) / 1_000_000),
                    "reason_code", operationCode == 0 ? "NONE" : stage.equals("DISPLAY") ? "DISPLAY_FAILED" : "CLI_FAILED"));
            } catch (IOException ex) { finalCode = committed || uncertain ? 74 : 73; }
            if (finalCode == 74) { System.err.println("ERROR: DB updated or commit outcome uncertain; audit incomplete. Do not rerun blindly; inspect op_id=" + base.get("op_id")); }
            else if (finalCode == 73) { System.err.println("ERROR: audit unavailable; operation was not committed"); }
            return finalCode;
        }
    }

    static String digest(final String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String osIdentity(final String option) throws Exception {
        final Process process = new ProcessBuilder("/usr/bin/id", option).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("OS_IDENTITY_TIMEOUT"); }
        final String value = new String(process.getInputStream().readNBytes(1024), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0 || value.isEmpty() || value.length() > 255
                || option.equals("-u") && !value.matches("[0-9]+")) { throw new IOException("OS_IDENTITY_UNAVAILABLE"); }
        return value;
    }
}
