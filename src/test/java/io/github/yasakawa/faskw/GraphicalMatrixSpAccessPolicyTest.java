/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSpAccessPolicyTest {
    @TempDir
    private Path temporary;

    @Test
    void evaluatesDenyFirstAndUsesOrWithinAnAttributeAndAndAcrossAttributes() {
        final GraphicalMatrixSpAccessPolicy.Policy policy = policy();
        final GraphicalMatrixSpAccessEvaluator evaluator =
            new GraphicalMatrixSpAccessEvaluator();

        assertEquals(GraphicalMatrixSpAccessEvaluator.Result.ALLOW,
            evaluator.evaluate(policy, policy.entityId(), Map.of(
                "businessCategory", List.of("AB"),
                "employeeStatus", List.of("active"),
                "accountStatus", List.of("normal"))).result());
        assertEquals("EXPLICIT_DENY",
            evaluator.evaluate(policy, policy.entityId(), Map.of(
                "businessCategory", List.of("AA"),
                "employeeStatus", List.of("active"),
                "accountStatus", List.of("suspended"))).reason());
        assertEquals("ATTRIBUTE_VALUE_MISMATCH",
            evaluator.evaluate(policy, policy.entityId(), Map.of(
                "businessCategory", List.of("XX"),
                "employeeStatus", List.of("active"))).reason());
        assertEquals("ATTRIBUTE_MISSING",
            evaluator.evaluate(policy, policy.entityId(), Map.of(
                "businessCategory", List.of("AA"))).reason());
    }

    @Test
    void roundTripsStrictPolicyJsonAndRejectsUnknownFields() throws Exception {
        final Path path = temporary.resolve("access-policy.json");
        GraphicalMatrixSpAccessPolicy.empty().with(policy()).save(path);
        final GraphicalMatrixSpAccessPolicy loaded =
            GraphicalMatrixSpAccessPolicy.load(path);
        assertEquals(1, loaded.policies().size());
        assertEquals(List.of("AA", "AB"),
            loaded.find("library").allow().get(0).values());

        Files.writeString(path, """
            {"schemaVersion":1,"policies":[{
              "name":"library",
              "entityId":"https://sp.example.org/shibboleth",
              "enabled":true,
              "mode":"restrict",
              "revision":1,
              "allow":[{"attributeId":"uid","values":["one"]}],
              "deny":[],
              "updatedAt":"2026-08-04T00:00:00Z",
              "typo":true
            }]}
            """);
        assertThrows(java.io.IOException.class,
            () -> GraphicalMatrixSpAccessPolicy.load(path));
    }

    @Test
    void rejectsUnsafeComparisonValuesAndDuplicateJsonKeys() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> new GraphicalMatrixSpAccessPolicy.Rule("uid", List.of(" AA")));
        assertThrows(java.io.IOException.class,
            () -> GraphicalMatrixJson.parse("{\"schemaVersion\":1,\"schemaVersion\":1}"));
    }

    private static GraphicalMatrixSpAccessPolicy.Policy policy() {
        return new GraphicalMatrixSpAccessPolicy.Policy(
            "library", "https://sp.example.org/shibboleth", true, "restrict", 1,
            List.of(
                new GraphicalMatrixSpAccessPolicy.Rule(
                    "businessCategory", List.of("AA")),
                new GraphicalMatrixSpAccessPolicy.Rule(
                    "businessCategory", List.of("AB")),
                new GraphicalMatrixSpAccessPolicy.Rule(
                    "employeeStatus", List.of("active"))),
            List.of(new GraphicalMatrixSpAccessPolicy.Rule(
                "accountStatus", List.of("suspended"))),
            Instant.parse("2026-08-04T00:00:00Z").toString());
    }
}
