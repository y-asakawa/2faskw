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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class StrictUtf8 {

    record Line(long offset, long nextOffset, byte[] bytes, boolean oversized, boolean terminated) {

        String decode() throws CharacterCodingException {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
    }

    private StrictUtf8() {
    }

    static Line readLine(final RandomAccessFile file) throws IOException {
        final long offset = file.getFilePointer();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean oversized = false;
        boolean terminated = false;
        int value;
        while ((value = file.read()) >= 0) {
            if (value == '\n') {
                terminated = true;
                break;
            }
            if (bytes.size() < AuditLogParser.MAX_LINE_BYTES + 1) {
                bytes.write(value);
            } else {
                oversized = true;
            }
        }
        if (value < 0 && file.getFilePointer() == offset) {
            return null;
        }
        final byte[] content = withoutTrailingCarriageReturn(bytes.toByteArray());
        return new Line(
                offset,
                file.getFilePointer(),
                content,
                oversized || content.length > AuditLogParser.MAX_LINE_BYTES,
                terminated);
    }

    static final class StreamLineReader implements AutoCloseable {

        private final InputStream input;
        private long offset;

        StreamLineReader(final InputStream input) {
            this.input = input;
        }

        Line readLine() throws IOException {
            final long lineOffset = offset;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean oversized = false;
            boolean terminated = false;
            int value;
            while ((value = input.read()) >= 0) {
                offset++;
                if (value == '\n') {
                    terminated = true;
                    break;
                }
                if (bytes.size() < AuditLogParser.MAX_LINE_BYTES + 1) {
                    bytes.write(value);
                } else {
                    oversized = true;
                }
            }
            if (value < 0 && offset == lineOffset) {
                return null;
            }
            final byte[] content = withoutTrailingCarriageReturn(bytes.toByteArray());
            return new Line(
                    lineOffset,
                    offset,
                    content,
                    oversized || content.length > AuditLogParser.MAX_LINE_BYTES,
                    terminated);
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static byte[] withoutTrailingCarriageReturn(final byte[] value) {
        if (value.length == 0 || value[value.length - 1] != '\r') {
            return value;
        }
        final byte[] trimmed = new byte[value.length - 1];
        System.arraycopy(value, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }
}
