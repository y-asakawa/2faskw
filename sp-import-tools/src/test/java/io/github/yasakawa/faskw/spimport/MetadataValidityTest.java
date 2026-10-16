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

package io.github.yasakawa.faskw.spimport;

import static io.github.yasakawa.faskw.spimport.ImportModel.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetadataValidityTest {
    Candidate candidate(String xml) {
        Candidate c = new Candidate(SourceAnalyzerTest.ENTITY, "snapshot.xml", "provider");
        c.bytes = xml.getBytes(StandardCharsets.UTF_8); return c;
    }
    @Test void acceptedSnapshotCanExpireAfterExportAndAtBoundary() throws Exception {
        Candidate c = candidate(SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"2030-01-01T00:00:00Z\" entityID="));
        MetadataValidity.check(List.of(c), Instant.parse("2029-12-31T23:59:59Z"));
        ImportException ex = assertThrows(ImportException.class, () -> MetadataValidity.check(List.of(c), Instant.parse("2030-01-01T00:00:00Z")));
        assertEquals(5, ex.exitCode); assertTrue(ex.getMessage().contains("SOURCE_METADATA_EXPIRED"));
    }
    @Test void roleExpiryIsCheckedEvenIfEntityExpiryIsLater() {
        Candidate c = candidate(SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"2040-01-01T00:00:00Z\" entityID=")
            .replace("<SPSSODescriptor", "<SPSSODescriptor validUntil=\"2000-01-01T00:00:00Z\""));
        assertEquals(5, assertThrows(ImportException.class, () -> MetadataValidity.check(List.of(c), Instant.parse("2030-01-01T00:00:00Z"))).exitCode);
    }
    @Test void malformedValidityAndWrongRootOrEntityAreRejected() {
        for (String xml : List.of(SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"not-a-date\" entityID="),
                SourceAnalyzerTest.METADATA.replace(SourceAnalyzerTest.ENTITY, "urn:other"), "<other/>")) {
            assertEquals(6, assertThrows(ImportException.class, () -> MetadataValidity.check(List.of(candidate(xml)), Instant.now())).exitCode);
        }
    }
    @Test void MissingValidityIsNotInventedAndForeignExtensionDateIsIgnored() throws Exception {
        MetadataValidity.check(List.of(candidate(SourceAnalyzerTest.METADATA)), Instant.parse("2100-01-01T00:00:00Z"));
        MetadataValidity.check(List.of(candidate(SourceAnalyzerTest.METADATA.replace("<SPSSODescriptor",
            "<Extensions><x:Notice xmlns:x=\"urn:example:extension\" validUntil=\"not-metadata\"/></Extensions><SPSSODescriptor"))), Instant.now());
    }
}
