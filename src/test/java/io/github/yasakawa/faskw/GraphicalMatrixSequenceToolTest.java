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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GraphicalMatrixSequenceToolTest {

    @Test
    void readsAndTrimsSequenceFromStandardInput() throws Exception {
        final ByteArrayInputStream input = new ByteArrayInputStream(
                "img01,img02,img03,img04\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "img01,img02,img03,img04",
                GraphicalMatrixSequenceTool.readSequenceInput(input));
    }

    @Test
    void rejectsOversizedStandardInput() {
        final ByteArrayInputStream input = new ByteArrayInputStream(
                "A".repeat(4097).getBytes(StandardCharsets.UTF_8));

        assertThrows(
                IllegalArgumentException.class,
                () -> GraphicalMatrixSequenceTool.readSequenceInput(input));
    }
}
