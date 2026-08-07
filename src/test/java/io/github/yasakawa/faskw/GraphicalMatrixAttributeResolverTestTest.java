/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixAttributeResolverTestTest {
    @Test
    void parsesIdpFiveAacliArrayOutput() throws Exception {
        final var attributes = GraphicalMatrixAttributeResolverTest.parseOutput("""
            {
              "requester":"https://sp.example.org/shibboleth",
              "principal":"user001",
              "attributes":[
                {"name":"uid","values":["user001"]},
                {"name":"businessCategory","values":["AA","AB"]}
              ]
            }
            """);

        assertEquals(List.of("user001"), attributes.get("uid"));
        assertEquals(List.of("AA", "AB"), attributes.get("businessCategory"));
    }

    @Test
    void rejectsDuplicateAacliAttributes() {
        assertThrows(IOException.class, () -> GraphicalMatrixAttributeResolverTest.parseOutput("""
            {"attributes":[
              {"name":"uid","values":["one"]},
              {"name":"uid","values":["two"]}
            ]}
            """));
    }
}
