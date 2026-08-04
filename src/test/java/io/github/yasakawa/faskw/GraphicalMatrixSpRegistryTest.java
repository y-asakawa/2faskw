/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixSpRegistryTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsEscapedRegistryValues() throws Exception {
        final GraphicalMatrixSpRegistry registry = GraphicalMatrixSpRegistry.empty();
        registry.put(entry("library", "https://sp.example.org/shibboleth"));
        final Path path = temporary.resolve("registry.json");
        registry.save(path);

        final GraphicalMatrixSpRegistry.Entry loaded = GraphicalMatrixSpRegistry.load(path).get("library");
        assertEquals("https://sp.example.org/shibboleth", loaded.entityId());
        assertEquals(List.of("uid"), List.of(loaded.attributeProfile()));
        assertEquals(List.of("AA:BB", "CC:DD"), loaded.certificateFingerprints());
        assertEquals("<MetadataProvider id=\"legacy\"/>", loaded.legacyProviderXml());
    }

    @Test
    void rejectsDuplicateEntityIdUnderAnotherName() {
        final GraphicalMatrixSpRegistry registry = GraphicalMatrixSpRegistry.empty();
        registry.put(entry("one", "https://sp.example.org/shibboleth"));
        assertThrows(IllegalArgumentException.class,
            () -> registry.put(entry("two", "https://sp.example.org/shibboleth")));
    }

    @Test
    void rejectsMalformedEscapedValuesWithoutSlowRegularExpressionMatching() throws Exception {
        final Path path = temporary.resolve("malformed-registry.json");
        final String escaped = "\\\\!".repeat(20_000);
        Files.writeString(path, """
            {"schemaVersion":1,"entries":[{"name":"%s"}]}
            """.formatted(escaped), StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> org.junit.jupiter.api.Assertions
            .assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> GraphicalMatrixSpRegistry.load(path)));
    }

    private static GraphicalMatrixSpRegistry.Entry entry(final String name, final String entityId) {
        return new GraphicalMatrixSpRegistry.Entry(name, entityId, "ACTIVE", "/secure/sp.xml",
            "abc", "metadata/file.xml", List.of("AA:BB", "CC:DD"),
            List.of("https://sp.example.org/acs"), "uid", "force", List.of(),
            "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", 1,
            "legacy", "metadata/legacy.xml", "<MetadataProvider id=\"legacy\"/>",
            "", "force");
    }
}
