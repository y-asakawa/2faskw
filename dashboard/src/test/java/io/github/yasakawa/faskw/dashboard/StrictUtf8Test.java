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
package io.github.yasakawa.faskw.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StrictUtf8Test {

    @Test
    void tracksOffsetsAndStripsCrLf() throws Exception {
        final byte[] content = "first\r\nsecond".getBytes(StandardCharsets.UTF_8);
        try (StrictUtf8.StreamLineReader reader =
                new StrictUtf8.StreamLineReader(new ByteArrayInputStream(content))) {
            final StrictUtf8.Line first = reader.readLine();
            final StrictUtf8.Line second = reader.readLine();

            assertEquals("first", first.decode());
            assertEquals(0, first.offset());
            assertEquals(7, first.nextOffset());
            assertTrue(first.terminated());
            assertEquals("second", second.decode());
            assertFalse(second.terminated());
        }
    }

    @Test
    void rejectsMalformedUtf8AndFlagsOversizedLines() throws Exception {
        final byte[] malformed = {(byte) 0xC3, (byte) 0x28, '\n'};
        try (StrictUtf8.StreamLineReader reader =
                new StrictUtf8.StreamLineReader(new ByteArrayInputStream(malformed))) {
            assertThrows(CharacterCodingException.class, () -> reader.readLine().decode());
        }

        final byte[] oversized = new byte[AuditLogParser.MAX_LINE_BYTES + 2];
        oversized[oversized.length - 1] = '\n';
        try (StrictUtf8.StreamLineReader reader =
                new StrictUtf8.StreamLineReader(new ByteArrayInputStream(oversized))) {
            assertTrue(reader.readLine().oversized());
        }
    }
}
