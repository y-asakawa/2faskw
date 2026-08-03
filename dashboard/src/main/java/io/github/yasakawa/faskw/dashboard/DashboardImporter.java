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
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class DashboardImporter {

    public record ImportResult(
            long lines,
            long valid,
            long parseFailures,
            long accepted,
            long duplicates,
            Instant firstEvent,
            Instant lastEvent) {
    }

    private final AuditLogParser parser = new AuditLogParser();

    public ImportResult run(
            final Path input,
            final String nodeId,
            final DashboardStore store,
            final PrivacyFilter privacyFilter,
            final ReasonMapper reasonMapper,
            final boolean apply) throws Exception {
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
            throw new IllegalArgumentException("import file is not readable: " + input);
        }
        if (!nodeId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("node ID is invalid");
        }
        final String generation = digest(input);
        final EventNormalizer normalizer = new EventNormalizer(privacyFilter, reasonMapper);
        long lines = 0;
        long valid = 0;
        long failures = 0;
        long accepted = 0;
        long duplicates = 0;
        Instant first = null;
        Instant last = null;
        final List<NormalizedEvent> batch = new ArrayList<>(250);
        try (StrictUtf8.StreamLineReader reader = reader(input)) {
            StrictUtf8.Line sourceLine;
            while ((sourceLine = reader.readLine()) != null) {
                lines++;
                if (sourceLine.oversized()) {
                    failures++;
                    continue;
                }
                final String line;
                try {
                    line = sourceLine.decode();
                } catch (CharacterCodingException e) {
                    failures++;
                    continue;
                }
                try {
                    final RawAuditEvent raw = parser.parse(line);
                    valid++;
                    first = first == null || raw.occurredAt().isBefore(first) ? raw.occurredAt() : first;
                    last = last == null || raw.occurredAt().isAfter(last) ? raw.occurredAt() : last;
                    if (apply) {
                        batch.add(normalizer.normalize(
                                raw, nodeId, generation, sourceLine.offset(), line));
                        if (batch.size() == 250) {
                            final DashboardStore.IngestResult result =
                                    store.ingest(batch, "offline-import");
                            accepted += result.accepted();
                            duplicates += result.duplicates();
                            batch.clear();
                        }
                    }
                } catch (AuditLogParser.ParseException e) {
                    failures++;
                }
            }
        }
        if (apply && !batch.isEmpty()) {
            final DashboardStore.IngestResult result = store.ingest(batch, "offline-import");
            accepted += result.accepted();
            duplicates += result.duplicates();
        }
        return new ImportResult(lines, valid, failures, accepted, duplicates, first, last);
    }

    private static StrictUtf8.StreamLineReader reader(final Path input) throws IOException {
        final InputStream raw = Files.newInputStream(input);
        try {
            final InputStream decoded = input.getFileName().toString().endsWith(".gz")
                    ? new GZIPInputStream(raw) : raw;
            return new StrictUtf8.StreamLineReader(decoded);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    private static String digest(final Path input) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(input)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
