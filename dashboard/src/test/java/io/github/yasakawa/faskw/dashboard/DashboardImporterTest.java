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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardImporterTest {

    @TempDir
    Path temporary;

    @Test
    void dryRunCountsValidAndMalformedLinesWithoutAStore() throws Exception {
        final Path log = temporary.resolve("audit.log");
        Files.writeString(log, """
                ts=2026-07-29T23:59:00Z event=VERIFY user=user001 result=OK ip=192.0.2.1 session=s challenge=c detail=matched
                malformed
                """);
        final DashboardImporter.ImportResult result = new DashboardImporter().run(
                log,
                "node-01",
                null,
                new PrivacyFilter(
                        PrivacyFilter.UserMode.DROP, PrivacyFilter.IpMode.DROP, null),
                new ReasonMapper(null),
                false);

        assertEquals(2, result.lines());
        assertEquals(1, result.valid());
        assertEquals(1, result.parseFailures());
        assertEquals(0, result.accepted());
    }

    @Test
    void repeatedApplyDoesNotDoubleCountTheSameFile() throws Exception {
        final Path log = temporary.resolve("dedupe.log");
        Files.writeString(log,
                "ts=2026-07-29T23:59:00Z event=VERIFY user=user001 result=OK "
                        + "ip=192.0.2.1 session=s challenge=c detail=matched\n");
        final PrivacyFilter privacy = new PrivacyFilter(
                PrivacyFilter.UserMode.DROP, PrivacyFilter.IpMode.DROP, null);
        try (DashboardStore store =
                new DashboardStore("jdbc:h2:mem:import-dedupe;DB_CLOSE_DELAY=-1")) {
            final DashboardImporter importer = new DashboardImporter();
            final DashboardImporter.ImportResult first = importer.run(
                    log, "node-01", store, privacy, new ReasonMapper(null), true);
            final DashboardImporter.ImportResult second = importer.run(
                    log, "node-01", store, privacy, new ReasonMapper(null), true);

            assertEquals(1, first.accepted());
            assertEquals(0, second.accepted());
            assertEquals(1, second.duplicates());
        }
    }

    @Test
    void rejectsMalformedUtf8WithoutReplacingIt() throws Exception {
        final Path log = temporary.resolve("invalid-utf8.log");
        final byte[] valid = (
                "ts=2026-07-29T23:59:00Z event=VERIFY user=user001 result=OK "
                        + "ip=192.0.2.1 session=s challenge=c detail=matched\n")
                .getBytes(StandardCharsets.UTF_8);
        final byte[] content = new byte[valid.length + 3];
        System.arraycopy(valid, 0, content, 0, valid.length);
        content[valid.length] = (byte) 0xC3;
        content[valid.length + 1] = (byte) 0x28;
        content[valid.length + 2] = '\n';
        Files.write(log, content);

        final DashboardImporter.ImportResult result = new DashboardImporter().run(
                log,
                "node-01",
                null,
                new PrivacyFilter(
                        PrivacyFilter.UserMode.DROP, PrivacyFilter.IpMode.DROP, null),
                new ReasonMapper(null),
                false);

        assertEquals(2, result.lines());
        assertEquals(1, result.valid());
        assertEquals(1, result.parseFailures());
    }
}
