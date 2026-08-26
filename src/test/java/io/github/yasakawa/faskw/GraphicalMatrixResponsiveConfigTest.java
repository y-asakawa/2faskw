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

final class GraphicalMatrixResponsiveConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void defaultsToLegacyColumnCount() throws Exception {
        writeProperties("");

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertEquals(430, config.getMobileBreakpointPx());
        assertEquals(5, config.getMobileColumns());
        assertFalse(config.hasResponsiveColumnOverride());
    }

    @Test
    void loadsResponsiveOverride() throws Exception {
        writeProperties("""
            graphicalmatrix.mobile.breakpointPx = 430
            graphicalmatrix.mobile.columns = 4
            """);

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertEquals(430, config.getMobileBreakpointPx());
        assertEquals(4, config.getMobileColumns());
        assertTrue(config.hasResponsiveColumnOverride());
    }

    @Test
    void defaultsMobileColumnsToConfiguredNormalColumns() throws Exception {
        writeProperties("""
            graphicalmatrix.columns = 6
            graphicalmatrix.rows = 1
            graphicalmatrix.graphicals = img01-06
            """);

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        assertEquals(6, config.getMobileColumns());
        assertFalse(config.hasResponsiveColumnOverride());
    }

    @Test
    void acceptsBreakpointBoundaries() throws Exception {
        writeProperties("graphicalmatrix.mobile.breakpointPx = 240\n");
        assertEquals(240, GraphicalMatrixConfig.load(idpHome.toString()).getMobileBreakpointPx());

        writeProperties("graphicalmatrix.mobile.breakpointPx = 1024\n");
        assertEquals(1024, GraphicalMatrixConfig.load(idpHome.toString()).getMobileBreakpointPx());
    }

    @Test
    void rejectsInvalidResponsiveSettings() throws Exception {
        assertInvalid("graphicalmatrix.mobile.breakpointPx = 239\n");
        assertInvalid("graphicalmatrix.mobile.breakpointPx = 1025\n");
        assertInvalid("graphicalmatrix.mobile.breakpointPx = narrow\n");
        assertInvalid("graphicalmatrix.mobile.columns = 0\n");
        assertInvalid("graphicalmatrix.mobile.columns = 6\n");
        assertInvalid("graphicalmatrix.mobile.columns = four\n");
        assertInvalid("""
            graphicalmatrix.mobile.columns = 4
            graphicalmatrix.view.css.enabled = false
            """);
    }

    private void assertInvalid(final String properties) throws Exception {
        writeProperties(properties);
        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixConfig.load(idpHome.toString()));
    }

    private void writeProperties(final String value) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("graphicalmatrix.properties"), value);
    }
}
