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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixLdapResolverConfigTest {
    @TempDir
    private Path temporary;

    @Test
    void initializesAnLdapConnectorWithoutReplacingStaticConnectors() throws Exception {
        final Path resolver = resolver("""
              <DataConnector id="staticAttributes" xsi:type="Static"/>
            """);

        final GraphicalMatrixLdapResolverConfig.InitializationPlan plan =
            GraphicalMatrixLdapResolverConfig.inspectInitialization(resolver, "", "uid");
        assertEquals("graphicalmatrixLdap", plan.dataConnector());
        assertFalse(plan.alreadyConfigured());

        GraphicalMatrixLdapResolverConfig.initialize(resolver, "", "uid");
        final String content = Files.readString(resolver);
        assertTrue(content.contains("id=\"staticAttributes\""));
        assertTrue(content.contains("id=\"graphicalmatrixLdap\""));
        assertTrue(content.contains("xsi:type=\"LDAPDirectory\""));
        assertTrue(content.contains("(uid=$resolutionContext.principal)"));
        assertTrue(GraphicalMatrixLdapResolverConfig.inspectInitialization(
            resolver, "", "uid").alreadyConfigured());
    }

    @Test
    void refusesToReplaceAConflictingConnectorDuringInitialization() throws Exception {
        final Path resolver = resolver("""
              <DataConnector id="graphicalmatrixLdap" xsi:type="Static"/>
            """);

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> GraphicalMatrixLdapResolverConfig.inspectInitialization(
                resolver, "graphicalmatrixLdap", "uid"));
        assertTrue(exception.getMessage().contains("non-LDAP"));
    }

    @Test
    void addsManagedDefinitionAndUpdatesRestrictedReturnAttributes() throws Exception {
        final Path resolver = resolver("""
              <DataConnector id="localTestLdap" xsi:type="LDAPDirectory">
                <ReturnAttributes>uid mail</ReturnAttributes>
              </DataConnector>
            """);

        final GraphicalMatrixLdapResolverConfig.Plan plan =
            GraphicalMatrixLdapResolverConfig.inspect(resolver,
                "businessCategory", "businessCategory", "");
        assertEquals("localTestLdap", plan.dataConnector());
        assertTrue(plan.returnAttributesUpdated());
        assertFalse(plan.alreadyConfigured());

        GraphicalMatrixLdapResolverConfig.add(resolver,
            "businessCategory", "businessCategory", "localTestLdap");
        final String content = Files.readString(resolver);
        assertTrue(content.contains("BEGIN 2FAS-KW managed LDAP attribute businessCategory"));
        assertTrue(content.contains("id=\"businessCategory\""));
        assertTrue(content.contains("ref=\"localTestLdap\""));
        assertTrue(content.contains("attributeNames=\"businessCategory\""));
        assertTrue(content.contains("uid mail businessCategory"));

        final GraphicalMatrixLdapResolverConfig.Plan repeated =
            GraphicalMatrixLdapResolverConfig.inspect(resolver,
                "businessCategory", "businessCategory", "localTestLdap");
        assertTrue(repeated.alreadyConfigured());
        assertEquals(1, occurrences(content, "id=\"businessCategory\""));
    }

    @Test
    void requiresExplicitSelectionWhenMultipleLdapConnectorsExist() throws Exception {
        final Path resolver = resolver("""
              <DataConnector id="employees" xsi:type="LDAPDirectory"/>
              <DataConnector id="students" xsi:type="LDAPDirectory"/>
            """);

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> GraphicalMatrixLdapResolverConfig.inspect(resolver,
                "businessCategory", "businessCategory", ""));
        assertTrue(exception.getMessage().contains("--data-connector"));

        final GraphicalMatrixLdapResolverConfig.Plan selected =
            GraphicalMatrixLdapResolverConfig.inspect(resolver,
                "businessCategory", "businessCategory", "students");
        assertEquals("students", selected.dataConnector());
    }

    @Test
    void refusesToOverwriteAnExistingUnmanagedDefinition() throws Exception {
        final Path resolver = resolver("""
              <AttributeDefinition id="businessCategory" xsi:type="Simple"/>
              <DataConnector id="localTestLdap" xsi:type="LDAPDirectory"/>
            """);

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> GraphicalMatrixLdapResolverConfig.inspect(resolver,
                "businessCategory", "businessCategory", ""));
        assertTrue(exception.getMessage().contains("review the existing AttributeDefinition"));
    }

    private Path resolver(final String body) throws Exception {
        final Path path = temporary.resolve("attribute-resolver.xml");
        Files.writeString(path, """
            <?xml version="1.0" encoding="UTF-8"?>
            <AttributeResolver xmlns="urn:mace:shibboleth:2.0:resolver"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            %s
            </AttributeResolver>
            """.formatted(body));
        return path;
    }

    private static int occurrences(final String value, final String match) {
        return value.split(java.util.regex.Pattern.quote(match), -1).length - 1;
    }
}
