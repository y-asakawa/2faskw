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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixAttributeDiscoveryTest {
    @TempDir
    private Path temporary;

    @Test
    void followsDefaultRegistryImportsForSamlMappings() throws Exception {
        final Path attributes = temporary.resolve("conf/attributes");
        Files.createDirectories(attributes.resolve("custom"));
        Files.writeString(attributes.resolve("default-rules.xml"), """
            <beans xmlns="http://www.springframework.org/schema/beans">
              <import resource="inetOrgPerson.xml"/>
            </beans>
            """);
        Files.writeString(attributes.resolve("inetOrgPerson.xml"), """
            <beans xmlns="http://www.springframework.org/schema/beans">
              <bean parent="shibboleth.TranscodingProperties">
                <property name="properties"><props>
                  <prop key="id">uid</prop>
                  <prop key="transcoder">SAML2StringTranscoder</prop>
                  <prop key="saml2.name">urn:oid:0.9.2342.19200300.100.1.1</prop>
                </props></property>
              </bean>
              <bean parent="shibboleth.TranscodingProperties">
                <property name="properties"><props>
                  <prop key="id">mail</prop>
                  <prop key="transcoder">SAML2StringTranscoder</prop>
                  <prop key="saml2.name">urn:oid:0.9.2342.19200300.100.1.3</prop>
                </props></property>
              </bean>
            </beans>
            """);
        final GraphicalMatrixSpManagementConfig config =
            GraphicalMatrixSpManagementConfig.load(temporary.toString());
        final Map<String, GraphicalMatrixAttributeDiscovery.Candidate> discovered =
            GraphicalMatrixAttributeDiscovery.discover(config,
                GraphicalMatrixAttributeCatalog.defaults(), GraphicalMatrixSpRegistry.empty(),
                GraphicalMatrixSpAccessPolicy.empty()).stream()
                .collect(java.util.stream.Collectors.toMap(
                    GraphicalMatrixAttributeDiscovery.Candidate::id, value -> value));

        assertEquals("mapped", discovered.get("uid").samlMapping());
        assertEquals("mapped", discovered.get("mail").samlMapping());
    }
}
