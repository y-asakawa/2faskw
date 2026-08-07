/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSpAccessPolicyStoreTest {
    @TempDir
    private Path temporary;

    @Test
    void retainsLastKnownGoodPolicyUntilRegistryRevisionMatches() throws Exception {
        final Path configDirectory = temporary.resolve("conf/graphicalmatrix");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("sp-management.properties"), """
            graphicalmatrix.sp.management.enabled = true
            graphicalmatrix.sp.access.enabled = true
            graphicalmatrix.sp.access.reloadIntervalSeconds = 1
            """);

        final Path catalogPath = configDirectory.resolve("attribute-catalog.json");
        GraphicalMatrixAttributeCatalog.defaults().save(catalogPath);
        final Path registryPath = configDirectory.resolve("sp-management-registry.json");
        registry(1).save(registryPath);
        final Path policyPath = configDirectory.resolve("access-policy.json");
        accessPolicy(1).save(policyPath);

        final GraphicalMatrixSpAccessPolicyStore store =
            new GraphicalMatrixSpAccessPolicyStore(
                GraphicalMatrixSpManagementConfig.load(temporary.toString()));
        assertEquals(1, store.policyFor(entityId()).revision());

        accessPolicy(2).save(policyPath);
        Thread.sleep(1_100L);
        assertEquals(1, store.policyFor(entityId()).revision(),
            "a policy-only partial update must retain the last known good snapshot");

        registry(2).save(registryPath);
        Thread.sleep(1_100L);
        assertEquals(2, store.policyFor(entityId()).revision());
    }

    private static GraphicalMatrixSpAccessPolicy accessPolicy(final int revision) {
        return GraphicalMatrixSpAccessPolicy.empty().with(
            new GraphicalMatrixSpAccessPolicy.Policy(
                "library", entityId(), true, "restrict", revision,
                List.of(new GraphicalMatrixSpAccessPolicy.Rule("uid", List.of("user001"))),
                List.of(), Instant.now().toString()));
    }

    private static GraphicalMatrixSpRegistry registry(final int accessRevision) {
        final GraphicalMatrixSpRegistry registry = GraphicalMatrixSpRegistry.empty();
        registry.put(new GraphicalMatrixSpRegistry.Entry(
            "library", entityId(), "ACTIVE", "/secure/sp.xml", "abc",
            "metadata/file.xml", List.of(), List.of("https://sp.example.org/acs"),
            "uid", "force", List.of(), "2026-08-03T00:00:00Z",
            "2026-08-03T00:00:00Z", 1, "", "", "", "", "", 0, true,
            accessRevision));
        return registry;
    }

    private static String entityId() {
        return "https://sp.example.org/shibboleth";
    }
}
