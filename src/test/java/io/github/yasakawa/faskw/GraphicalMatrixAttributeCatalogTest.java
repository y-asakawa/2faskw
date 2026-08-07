/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixAttributeCatalogTest {
    @TempDir
    private Path temporary;

    @Test
    void keepsInternalOnlyAttributesOutOfReleaseProfiles() {
        final GraphicalMatrixAttributeCatalog catalog =
            GraphicalMatrixAttributeCatalog.defaults().withAttribute(
                new GraphicalMatrixAttributeCatalog.Attribute(
                    "businessCategory", "declared", "mapping-required",
                    "internal-only", "internal", "SP authorization",
                    Instant.now().toString()));

        assertTrue(catalog.requireAttribute("businessCategory").accessApproved());
        assertThrows(IllegalArgumentException.class, () -> catalog.withProfile(
            new GraphicalMatrixAttributeCatalog.Profile(
                "business-access", List.of("businessCategory"), "invalid release",
                1, "managed", Instant.now().toString())));
    }

    @Test
    void savesAndLoadsApprovedMappedProfiles() throws Exception {
        final GraphicalMatrixAttributeCatalog catalog =
            GraphicalMatrixAttributeCatalog.defaults().withProfile(
                new GraphicalMatrixAttributeCatalog.Profile(
                    "account", List.of("uid", "mail"), "Account attributes",
                    1, "managed", Instant.now().toString()));
        final Path path = temporary.resolve("attribute-catalog.json");
        catalog.save(path);
        final GraphicalMatrixAttributeCatalog loaded =
            GraphicalMatrixAttributeCatalog.load(path);
        assertEquals(List.of("uid", "mail"),
            loaded.findProfile("account").attributes());
    }

    @Test
    void appliesBuiltInAndOrganizationBlockedRules() {
        final GraphicalMatrixAttributeCatalog catalog =
            GraphicalMatrixAttributeCatalog.defaults();
        assertTrue(catalog.isBlocked("userPassword", Set.of()));
        assertTrue(catalog.isBlocked("hrPrivateCode", Set.of("hrPrivateCode")));
    }
}
