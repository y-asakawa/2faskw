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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixSpMetadataTest {
    private static final String ENTITY_ID = "https://sp.example.org/shibboleth";

    @TempDir
    Path temporary;

    @Test
    void parsesOneSpAndCalculatesStandardFileName() throws Exception {
        final GraphicalMatrixSpManagementConfig config = config("sp.example.org");
        final Path metadata = Files.writeString(temporary.resolve("sp.xml"), metadata(
            ENTITY_ID, "https://sp.example.org/Shibboleth.sso/SAML2/POST"));

        final GraphicalMatrixSpMetadata.Parsed parsed =
            GraphicalMatrixSpMetadata.fromFile(config, metadata, ENTITY_ID);

        assertEquals(ENTITY_ID, parsed.entityId());
        assertEquals(1, parsed.acsUrls().size());
        assertEquals(64, parsed.sha256().length());
        assertTrue(GraphicalMatrixSpMetadata.standardFileName(ENTITY_ID).matches("[0-9a-f]{40}\\.xml"));
    }

    @Test
    void rejectsEntityMismatchDoctypeHttpAndUnapprovedAcs() throws Exception {
        final GraphicalMatrixSpManagementConfig config = config("sp.example.org");
        final Path mismatch = Files.writeString(temporary.resolve("mismatch.xml"), metadata(
            "https://other.example.org/shibboleth",
            "https://sp.example.org/Shibboleth.sso/SAML2/POST"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMetadata.fromFile(config, mismatch, ENTITY_ID));

        final Path doctype = Files.writeString(temporary.resolve("doctype.xml"),
            "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + metadata(
                ENTITY_ID, "https://sp.example.org/acs"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMetadata.fromFile(config, doctype, ENTITY_ID));

        final Path http = Files.writeString(temporary.resolve("http.xml"), metadata(
            ENTITY_ID, "http://sp.example.org/acs"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMetadata.fromFile(config, http, ENTITY_ID));

        final Path foreign = Files.writeString(temporary.resolve("foreign.xml"), metadata(
            ENTITY_ID, "https://unapproved.example.org/acs"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSpMetadata.fromFile(config, foreign, ENTITY_ID));
        assertEquals(ENTITY_ID, GraphicalMatrixSpMetadata.inspectExisting(config, foreign).entityId());
    }

    private GraphicalMatrixSpManagementConfig config(final String acsHost) throws Exception {
        final Path directory = temporary.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("sp-management.properties"),
            "graphicalmatrix.sp.metadata.allowedAcsHosts = " + acsHost + "\n"
                + "graphicalmatrix.sp.reload.enabled = false\n", StandardCharsets.UTF_8);
        return GraphicalMatrixSpManagementConfig.load(temporary.toString());
    }

    static String metadata(final String entityId, final String acs) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
                entityID="%s">
              <md:SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol"
                  AuthnRequestsSigned="true">
                <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                    Location="%s" index="1"/>
              </md:SPSSODescriptor>
            </md:EntityDescriptor>
            """.formatted(entityId, acs);
    }
}
