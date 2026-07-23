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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSaveDataConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void defaultsToDbWhenUnset() throws Exception {
        Files.createDirectories(idpHome.resolve("conf/graphicalmatrix"));
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/graphicalmatrix.properties"), "");

        assertEquals("db", GraphicalMatrixSaveDataConfig.load(idpHome.toString()).mode());
    }

    @Test
    void acceptsLdapMode() throws Exception {
        Files.createDirectories(idpHome.resolve("conf/graphicalmatrix"));
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.savedata = ldap\n");

        assertTrue(GraphicalMatrixSaveDataConfig.load(idpHome.toString()).isLdap());
    }

    @Test
    void rejectsUnsupportedMode() throws Exception {
        Files.createDirectories(idpHome.resolve("conf/graphicalmatrix"));
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.savedata = file\n");

        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixSaveDataConfig.load(idpHome.toString()));
    }
}
