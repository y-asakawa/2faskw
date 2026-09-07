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

final class GraphicalMatrixSecurityHeadersConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void defaultsToEnforcedSecurityHeaders() throws Exception {
        writeProperties("");

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertTrue(config.isSecurityHeadersEnabled());
        assertTrue(config.isSecurityHeadersCspEnforced());
        assertEquals("enforce", config.getSecurityHeadersCspMode());
        assertEquals(0, config.getJavascriptCacheSeconds());
    }

    @Test
    void acceptsExplicitReportOnlyConfigurationAndJavascriptPath() throws Exception {
        final Path javascript = idpHome.resolve("custom.js");
        writeProperties("""
            graphicalmatrix.securityHeaders.enabled = false
            graphicalmatrix.securityHeaders.cspMode = report-only
            graphicalmatrix.view.javascript = %s
            graphicalmatrix.view.javascript.cacheSeconds = 60
            """.formatted(javascript));

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertFalse(config.isSecurityHeadersEnabled());
        assertFalse(config.isSecurityHeadersCspEnforced());
        assertEquals("report-only", config.getSecurityHeadersCspMode());
        assertEquals(javascript, config.getJavascriptPath());
        assertEquals(60, config.getJavascriptCacheSeconds());
    }

    @Test
    void rejectsUnknownSecurityHeaderConfiguration() throws Exception {
        writeProperties("graphicalmatrix.securityHeaders.enabled = maybe\n");
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));

        writeProperties("graphicalmatrix.securityHeaders.cspMode = disabled\n");
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));

        writeProperties("graphicalmatrix.view.javascript.cacheSeconds = -1\n");
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    private void writeProperties(final String value) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), value);
    }
}
