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

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixSelfServiceResourcesTest {
    @Test
    void pluginContainsWellFormedSelfServiceFlowResources() throws Exception {
        assertXml("META-INF/net.shibboleth.idp/postconfig.xml");
        assertXml("META-INF/net/shibboleth/idp/flows/2faskw/self-service/self-service-flow.xml");
        assertXml("META-INF/net/shibboleth/idp/flows/2faskw/self-service/self-service-beans.xml");
    }

    @Test
    void administrativeFlowUsesAuthenticationFlowSuffix() throws Exception {
        final String resource = "META-INF/net.shibboleth.idp/postconfig.xml";
        try (InputStream input = GraphicalMatrixSelfServiceResourcesTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            final String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("<value>MFA</value>"));
            assertFalse(xml.contains("<value>authn/MFA</value>"));
        }
    }

    private static void assertXml(final String resource) throws Exception {
        try (InputStream input = GraphicalMatrixSelfServiceResourcesTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            assertTrue(factory.newDocumentBuilder().parse(input).getDocumentElement().hasChildNodes());
        }
    }
}
