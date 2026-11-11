/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Root-controlled, append-only-at-the-application-level sink; not a tamper-proof ledger. */
public final class GraphicalMatrixAdminAuditWriter {
    static final int MAX_RECORD = 8192;
    static final int MAX_BATCH = 64 * 1024 * 1024;
    private static final Set<String> KEYS = Set.of("format", "ts", "event", "op_id", "parent_op_id",
        "seq", "host", "deployment_id", "action", "mode", "target_user", "uid", "euid",
        "effective_user", "login_uid", "invoker_claim", "invoker_source", "db_type", "db_target_id",
        "stage", "result", "db_result", "matched_rows", "changed_rows", "changes", "exit_code",
        "operation_exit_code", "duration_ms", "reason_code");
    private final Path log;
    private final Path lock;
    private final long timeoutMillis;
    private final long owner;
    private final String group;
    private final boolean mirror;
    private final boolean strictParents;

    public GraphicalMatrixAdminAuditWriter(final Path home) throws IOException {
        this(settings(home), 0, "graphicalmatrix-admin", true);
    }

    // Explicit ownership injection is package-private and used only by filesystem tests.
    GraphicalMatrixAdminAuditWriter(final Properties p, final long owner, final String group) throws IOException {
        this(p, owner, group, false);
    }

    private GraphicalMatrixAdminAuditWriter(final Properties p, final long owner, final String group,
            final boolean strictParents) throws IOException {
        log = Path.of(p.getProperty("graphicalmatrix.admin.audit.logFile", "/var/log/2faskw/admin-audit.log")).normalize();
        lock = Path.of(p.getProperty("graphicalmatrix.admin.audit.lockFile", "/var/log/2faskw/admin-audit.lock")).normalize();
        try { timeoutMillis = Long.parseLong(p.getProperty("graphicalmatrix.admin.audit.lockTimeoutMillis", "3000")); }
        catch (NumberFormatException ex) { throw new IOException("INVALID_AUDIT_TIMEOUT"); }
        if (timeoutMillis < 100 || timeoutMillis > 10000 || !log.isAbsolute() || !lock.isAbsolute() || log.equals(lock)
                || !log.getParent().equals(lock.getParent())) { throw new IOException("INVALID_AUDIT_SETTINGS"); }
        final String mirrorValue = p.getProperty("graphicalmatrix.admin.audit.mirrorSyslog", "false");
        if (!Set.of("true", "false").contains(mirrorValue)) { throw new IOException("INVALID_AUDIT_SETTINGS"); }
        mirror = Boolean.parseBoolean(mirrorValue);
        this.owner = owner;
        this.group = group;
        this.strictParents = strictParents;
    }

    private static Properties settings(final Path home) throws IOException {
        final Path path = home.resolve("conf/graphicalmatrix/admin-audit.properties");
        final Properties p = new Properties();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != 0
                    || (((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 0022) != 0) {
                throw new IOException("UNTRUSTED_AUDIT_CONFIGURATION");
            }
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
        }
        return p;
    }

    static byte[] encode(final Map<String, String> fields) throws IOException {
        final StringBuilder line = new StringBuilder("format=1 ts=" + GraphicalMatrixLogTimestamp.now());
        for (var entry : fields.entrySet()) {
            if (!KEYS.contains(entry.getKey()) || Set.of("format", "ts").contains(entry.getKey())) {
                throw new IOException("INVALID_AUDIT_FIELD");
            }
            line.append(' ').append(entry.getKey()).append('=').append(escape(entry.getValue()));
        }
        final byte[] bytes = line.append('\n').toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECORD) { throw new IOException("AUDIT_RECORD_LIMIT"); }
        return bytes;
    }

    static String escape(final String value) {
        final StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            final int c = b & 255;
            if (c >= 33 && c <= 126 && c != '%' && c != '=') { out.append((char) c); }
            else { out.append('%').append("0123456789ABCDEF".charAt(c >>> 4)).append("0123456789ABCDEF".charAt(c & 15)); }
        }
        return out.toString();
    }

    public void append(final List<Map<String, String>> records) throws IOException {
        final List<byte[]> batch = new ArrayList<>(records.size());
        long size = 0;
        for (var record : records) {
            final byte[] bytes = encode(record);
            size += bytes.length;
            if (size > MAX_BATCH) { throw new IOException("AUDIT_BATCH_LIMIT"); }
            batch.add(bytes);
        }
        validateParents();
        validateFile(lock);
        try (FileChannel lockChannel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            final long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
            FileLock held = null;
            while (held == null) {
                try { held = lockChannel.tryLock(); } catch (OverlappingFileLockException ignored) { }
                if (held == null) {
                    if (System.nanoTime() >= deadline) { throw new IOException("AUDIT_LOCK_TIMEOUT"); }
                    pause();
                }
            }
            try (FileLock ignored = held) {
                final long rotationDeadline = System.nanoTime() + 1_000_000_000L;
                while (true) {
                    try {
                        validateFile(log);
                        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                            for (byte[] record : batch) {
                                final ByteBuffer buffer = ByteBuffer.wrap(record);
                                while (buffer.hasRemaining()) { channel.write(buffer); }
                            }
                            channel.force(true);
                        }
                        break;
                    } catch (NoSuchFileException ex) {
                        if (System.nanoTime() >= rotationDeadline) { throw new IOException("AUDIT_LOG_MISSING"); }
                        pause();
                    }
                }
            }
        }
        if (mirror) {
            // One bounded best-effort mirror per batch, not one process (or timeout) per CSV row.
            Process process = null;
            try {
                process = new ProcessBuilder("/usr/bin/logger", "-t", "2faskw-admin")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                final Process logger = process;
                final java.util.concurrent.atomic.AtomicBoolean transferFailed = new java.util.concurrent.atomic.AtomicBoolean();
                final Thread transfer = Thread.ofVirtual().start(() -> {
                    try (var out = logger.getOutputStream()) { for (byte[] record : batch) { out.write(record); } }
                    catch (IOException ex) { transferFailed.set(true); }
                });
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException(); }
                transfer.join(100);
                if (process.exitValue() != 0 || transferFailed.get() || transfer.isAlive()) { throw new IOException(); }
            } catch (Exception ex) {
                System.err.println("WARNING: audit syslog mirror failed; local durable audit retained");
            } finally { if (process != null && process.isAlive()) { process.destroyForcibly(); } }
        }
    }

    private void validateParents() throws IOException {
        for (Path p = log.getParent(); p != null; p = p.getParent()) {
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p)) {
                throw new IOException("UNTRUSTED_AUDIT_DIRECTORY");
            }
            final long uid = ((Number) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
            final int mode = ((Number) Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            if (uid != owner || (mode & 0022) != 0) { throw new IOException("UNTRUSTED_AUDIT_DIRECTORY"); }
            // The injectable test constructor trusts its private fixture root, even in root-run CI.
            if (!strictParents) { break; }
        }
    }

    private void validateFile(final Path path) throws IOException {
        final Map<String, Object> a = Files.readAttributes(path, "unix:uid,nlink,mode", LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || ((Number) a.get("uid")).longValue() != owner
                || ((Number) a.get("nlink")).longValue() != 1
                || (((Number) a.get("mode")).intValue() & 0777) != 0660
                || !Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).group().getName().equals(group)) {
            throw new IOException("UNTRUSTED_AUDIT_FILE");
        }
    }

    private static void pause() throws IOException {
        try { Thread.sleep(10); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("AUDIT_INTERRUPTED"); }
    }
}
