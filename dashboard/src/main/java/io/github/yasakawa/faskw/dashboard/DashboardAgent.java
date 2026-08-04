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
package io.github.yasakawa.faskw.dashboard;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DashboardAgent {

    private static final String VERSION = implementationVersion();

    private final AgentConfig config;
    private final AuditLogParser parser;
    private final EventNormalizer normalizer;
    private final HttpClient client;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Instant lastSuccessfulContact = Instant.EPOCH;

    public DashboardAgent(final AgentConfig config) throws Exception {
        this.config = config;
        parser = new AuditLogParser();
        final byte[] hmacKey = config.hmacKeyFile() == null
                ? null : Files.readAllBytes(config.hmacKeyFile());
        normalizer = new EventNormalizer(
                new PrivacyFilter(config.userMode(), config.ipMode(), hmacKey),
                new ReasonMapper(config.reasonMappingFile()));
        client = HttpClient.newBuilder()
                .sslContext(TlsSupport.clientContext(
                        config.keyStore(),
                        config.keyStorePasswordFile(),
                        config.trustStore(),
                        config.trustStorePasswordFile()))
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public void run() throws Exception {
        if (!config.enabled()) {
            throw new IllegalArgumentException(
                    "agent.enabled=false; validate configuration before enabling the Agent");
        }
        Files.createDirectories(config.statePath());
        Files.createDirectories(config.spoolPath());
        long retryMillis = config.retryInitialMillis();
        while (running.get()) {
            try {
                boolean sent = sendOldestSpool();
                readAndSpool();
                if (!sent) {
                    sent = sendOldestSpool();
                }
                if (!sent) {
                    sent = sendHeartbeatIfDue();
                }
                if (sent) {
                    retryMillis = config.retryInitialMillis();
                }
                Thread.sleep(config.pollMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("dashboard agent retrying after error: " + safeMessage(e));
                Thread.sleep(retryMillis);
                retryMillis = Math.min(config.retryMaxMillis(), retryMillis * 2);
            }
        }
    }

    public void stop() {
        running.set(false);
    }

    private void readAndSpool() throws Exception {
        final AgentState state = loadState();
        final BasicFileAttributes attributes =
                Files.readAttributes(config.source(), BasicFileAttributes.class);
        final String fileKey = String.valueOf(attributes.fileKey());
        long offset = state.offset();
        long generation = state.generation();
        if (!fileKey.equals(state.fileKey()) || attributes.size() < offset) {
            generation++;
            offset = 0;
        }

        final List<NormalizedEvent> events = new ArrayList<>(config.batchMaxEvents());
        long committedOffset = offset;
        long parseFailures = 0;
        Instant flushDeadline = null;
        try (RandomAccessFile file = new RandomAccessFile(config.source().toFile(), "r")) {
            file.seek(offset);
            while (events.size() < config.batchMaxEvents()) {
                final StrictUtf8.Line sourceLine = StrictUtf8.readLine(file);
                if (sourceLine == null || !sourceLine.terminated()) {
                    if (sourceLine != null) {
                        file.seek(sourceLine.offset());
                    }
                    if (events.isEmpty() || !waitForBatch(flushDeadline)) {
                        break;
                    }
                    continue;
                }
                committedOffset = sourceLine.nextOffset();
                if (sourceLine.oversized()) {
                    parseFailures++;
                    System.err.println("dashboard agent discarded oversized audit line at offset "
                            + sourceLine.offset());
                    continue;
                }
                final String line;
                try {
                    line = sourceLine.decode();
                } catch (CharacterCodingException e) {
                    parseFailures++;
                    System.err.println("dashboard agent discarded invalid UTF-8 at offset "
                            + sourceLine.offset());
                    continue;
                }
                try {
                    final RawAuditEvent raw = parser.parse(line);
                    events.add(normalizer.normalize(
                            raw,
                            config.nodeId(),
                            Long.toString(generation),
                            sourceLine.offset(),
                            line));
                    if (flushDeadline == null) {
                        flushDeadline = Instant.now().plusMillis(config.batchFlushMillis());
                    }
                } catch (AuditLogParser.ParseException e) {
                    parseFailures++;
                    System.err.println("dashboard agent discarded malformed audit line at offset "
                            + sourceLine.offset() + ": " + e.getMessage());
                }
                if (flushDeadline != null && !Instant.now().isBefore(flushDeadline)) {
                    break;
                }
            }
        }
        if (!events.isEmpty()) {
            writeSpool(events, state.pendingParseFailures() + parseFailures);
        }
        if (committedOffset != offset || generation != state.generation()
                || !fileKey.equals(state.fileKey())) {
            saveState(new AgentState(
                    fileKey,
                    generation,
                    committedOffset,
                    events.isEmpty() ? state.pendingParseFailures() + parseFailures : 0));
        }
    }

    private boolean waitForBatch(final Instant flushDeadline) throws InterruptedException {
        if (flushDeadline == null) {
            return false;
        }
        final long remaining = Duration.between(Instant.now(), flushDeadline).toMillis();
        if (remaining <= 0) {
            return false;
        }
        Thread.sleep(Math.min(config.pollMillis(), remaining));
        return true;
    }

    private void writeSpool(
            final List<NormalizedEvent> events,
            final long parseFailureCount) throws Exception {
        final IngestBatch batch = new IngestBatch(
                1,
                config.nodeId(),
                VERSION,
                currentSpoolBytes(),
                parseFailureCount,
                0,
                List.copyOf(events));
        final byte[] body = JsonSupport.MAPPER.writeValueAsBytes(batch);
        enforceSpoolLimit(body.length);
        final String name = String.format(
                "%019d-%s.json", System.currentTimeMillis(), events.getFirst().eventId());
        final Path target = config.spoolPath().resolve(name);
        final Path temporary = config.spoolPath().resolve("." + name + ".tmp");
        Files.write(temporary, body);
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean sendOldestSpool() throws Exception {
        final List<Path> batches = spoolFiles();
        if (batches.isEmpty()) {
            return false;
        }
        final Path batch = batches.getFirst();
        final byte[] body = Files.readAllBytes(batch);
        final HttpRequest request = HttpRequest.newBuilder(config.dashboardUrl())
                .timeout(Duration.ofMillis(config.requestTimeoutMillis()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        final HttpResponse<String> response = send(request);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            Files.deleteIfExists(batch);
            lastSuccessfulContact = Instant.now();
            return true;
        }
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            final Path rejected = batch.resolveSibling(batch.getFileName() + ".rejected");
            Files.move(batch, rejected, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("dashboard agent quarantined a rejected batch: HTTP "
                    + response.statusCode());
            return false;
        }
        throw new IOException("Dashboard returned HTTP " + response.statusCode());
    }

    private boolean sendHeartbeatIfDue() throws Exception {
        final Instant now = Instant.now();
        if (lastSuccessfulContact.plusSeconds(config.heartbeatSeconds()).isAfter(now)) {
            return false;
        }
        final IngestBatch heartbeat = new IngestBatch(
                1,
                config.nodeId(),
                VERSION,
                currentSpoolBytes(),
                0,
                0,
                List.of());
        final byte[] body = JsonSupport.MAPPER.writeValueAsBytes(heartbeat);
        final HttpRequest request = HttpRequest.newBuilder(config.dashboardUrl())
                .timeout(Duration.ofMillis(config.requestTimeoutMillis()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        final HttpResponse<String> response = send(request);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            lastSuccessfulContact = now;
            return true;
        }
        throw new IOException("Dashboard heartbeat returned HTTP " + response.statusCode());
    }

    private HttpResponse<String> send(final HttpRequest request)
            throws IOException, InterruptedException {
        return client.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void enforceSpoolLimit(final long incomingBytes) throws IOException {
        long current = currentSpoolBytes();
        final List<Path> files = spoolFiles();
        int index = 0;
        while (current + incomingBytes > config.spoolMaxBytes() && index < files.size()) {
            final Path oldest = files.get(index++);
            final long size = Files.size(oldest);
            Files.deleteIfExists(oldest);
            current -= size;
            System.err.println("dashboard agent dropped oldest spool batch at configured limit");
        }
        if (current + incomingBytes > config.spoolMaxBytes()) {
            throw new IOException("single Agent batch exceeds spool maximum");
        }
    }

    private long currentSpoolBytes() throws IOException {
        long bytes = 0;
        for (Path file : spoolFiles()) {
            bytes += Files.size(file);
        }
        return bytes;
    }

    private List<Path> spoolFiles() throws IOException {
        final List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(config.spoolPath())) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                config.spoolPath(), path -> path.getFileName().toString().endsWith(".json"))) {
            for (Path path : stream) {
                files.add(path);
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private AgentState loadState() throws IOException {
        final Path path = stateFile();
        if (!Files.isRegularFile(path)) {
            return new AgentState("", 0, 0, 0);
        }
        final Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        try {
            return new AgentState(
                    properties.getProperty("fileKey", ""),
                    Long.parseLong(properties.getProperty("generation", "0")),
                    Long.parseLong(properties.getProperty("offset", "0")),
                    Long.parseLong(properties.getProperty("pendingParseFailures", "0")));
        } catch (NumberFormatException e) {
            throw new IOException("Agent state file is invalid", e);
        }
    }

    private void saveState(final AgentState state) throws IOException {
        final Properties properties = new Properties();
        properties.setProperty("fileKey", state.fileKey());
        properties.setProperty("generation", Long.toString(state.generation()));
        properties.setProperty("offset", Long.toString(state.offset()));
        properties.setProperty(
                "pendingParseFailures", Long.toString(state.pendingParseFailures()));
        final Path path = stateFile();
        final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary)) {
            properties.store(output, "2FAS-KW Dashboard Agent state");
        }
        try {
            Files.move(temporary, path,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path stateFile() {
        return config.statePath().resolve("agent-state.properties");
    }

    private static String implementationVersion() {
        final String version =
                DashboardAgent.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private static String safeMessage(final Exception exception) {
        final String value = exception.getMessage();
        return value == null ? exception.getClass().getSimpleName()
                : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private record AgentState(
            String fileKey,
            long generation,
            long offset,
            long pendingParseFailures) {
    }
}
