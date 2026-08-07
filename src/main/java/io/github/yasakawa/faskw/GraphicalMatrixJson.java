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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small bounded JSON codec used by the local-only management tools. */
final class GraphicalMatrixJson {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ITEMS = 100_000;
    private static final int MAX_STRING_CHARS = 262_144;

    private GraphicalMatrixJson() {
    }

    static Map<String, Object> readObject(final Path path, final int maxBytes) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("JSON file is missing or is not a regular file: " + path);
        }
        final long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException("JSON file exceeds maximum size: " + path);
        }
        final Object value = parse(Files.readString(path, StandardCharsets.UTF_8));
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException("JSON root must be an object: " + path);
        }
        return stringObject(map, "JSON root");
    }

    static Object parse(final String json) throws IOException {
        if (json == null) {
            throw new IOException("JSON input is null");
        }
        final Parser parser = new Parser(json);
        final Object value = parser.value(0);
        parser.whitespace();
        if (!parser.end()) {
            throw parser.error("unexpected content after JSON value");
        }
        return value;
    }

    static String write(final Object value) {
        final StringBuilder out = new StringBuilder(1024);
        append(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    static Map<String, Object> object(final Map<String, Object> root, final String key,
            final boolean required) throws IOException {
        final Object value = root.get(key);
        if (value == null && !required) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException("JSON field must be an object: " + key);
        }
        return stringObject(map, key);
    }

    static List<Object> array(final Map<String, Object> root, final String key,
            final boolean required) throws IOException {
        final Object value = root.get(key);
        if (value == null && !required) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IOException("JSON field must be an array: " + key);
        }
        return List.copyOf(list);
    }

    static String string(final Map<String, Object> root, final String key,
            final boolean required) throws IOException {
        final Object value = root.get(key);
        if (value == null && !required) {
            return "";
        }
        if (!(value instanceof String text) || required && text.isBlank()) {
            throw new IOException("JSON field must be a non-empty string: " + key);
        }
        return text;
    }

    static int integer(final Map<String, Object> root, final String key,
            final int defaultValue) throws IOException {
        final Object value = root.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE
                || number > Integer.MAX_VALUE) {
            throw new IOException("JSON field must be an integer: " + key);
        }
        return number.intValue();
    }

    static boolean bool(final Map<String, Object> root, final String key,
            final boolean defaultValue) throws IOException {
        final Object value = root.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean result)) {
            throw new IOException("JSON field must be a boolean: " + key);
        }
        return result;
    }

    static List<String> strings(final Map<String, Object> root, final String key) throws IOException {
        final List<String> out = new ArrayList<>();
        for (final Object value : array(root, key, false)) {
            if (!(value instanceof String text)) {
                throw new IOException("JSON array must contain strings: " + key);
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    static Map<String, Object> asObject(final Object value, final String label) throws IOException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException("JSON array item must be an object: " + label);
        }
        return stringObject(map, label);
    }

    static void rejectUnknown(final Map<String, Object> object, final String label,
            final String... allowed) throws IOException {
        final List<String> names = List.of(allowed);
        for (final String key : object.keySet()) {
            if (!names.contains(key)) {
                throw new IOException("unknown JSON field in " + label + ": " + key);
            }
        }
    }

    private static Map<String, Object> stringObject(final Map<?, ?> map, final String label)
            throws IOException {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("JSON object key is not a string: " + label);
            }
            out.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    private static void append(final StringBuilder out, final Object value, final int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            string(out, text);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            object(out, map, indent);
        } else if (value instanceof List<?> list) {
            array(out, list, indent);
        } else {
            throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
        }
    }

    private static void object(final StringBuilder out, final Map<?, ?> map, final int indent) {
        out.append('{');
        if (!map.isEmpty()) {
            int index = 0;
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object key must be a string");
                }
                out.append(index++ == 0 ? '\n' : ",\n");
                spaces(out, indent + 2);
                string(out, key);
                out.append(": ");
                append(out, entry.getValue(), indent + 2);
            }
            out.append('\n');
            spaces(out, indent);
        }
        out.append('}');
    }

    private static void array(final StringBuilder out, final List<?> list, final int indent) {
        out.append('[');
        if (!list.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? '\n' : ",\n");
                spaces(out, indent + 2);
                append(out, list.get(i), indent + 2);
            }
            out.append('\n');
            spaces(out, indent);
        }
        out.append(']');
    }

    private static void spaces(final StringBuilder out, final int count) {
        out.append(" ".repeat(Math.max(0, count)));
    }

    private static void string(final StringBuilder out, final String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char item = value.charAt(i);
            switch (item) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (item < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) item));
                    } else {
                        out.append(item);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String input;
        private int offset;
        private int items;

        Parser(final String value) {
            input = value;
        }

        Object value(final int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw error("JSON nesting exceeds limit");
            }
            if (++items > MAX_ITEMS) {
                throw error("JSON item count exceeds limit");
            }
            whitespace();
            if (end()) {
                throw error("missing JSON value");
            }
            return switch (input.charAt(offset)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(final int depth) throws IOException {
            offset++;
            whitespace();
            final Map<String, Object> out = new LinkedHashMap<>();
            if (take('}')) {
                return Map.of();
            }
            while (true) {
                whitespace();
                if (end() || input.charAt(offset) != '"') {
                    throw error("expected JSON object key");
                }
                final String key = string();
                whitespace();
                expect(':');
                final Object value = value(depth);
                if (out.containsKey(key)) {
                    throw error("duplicate JSON object key: " + key);
                }
                out.put(key, value);
                whitespace();
                if (take('}')) {
                    return Collections.unmodifiableMap(out);
                }
                expect(',');
            }
        }

        private List<Object> array(final int depth) throws IOException {
            offset++;
            whitespace();
            final List<Object> out = new ArrayList<>();
            if (take(']')) {
                return List.of();
            }
            while (true) {
                out.add(value(depth));
                whitespace();
                if (take(']')) {
                    return Collections.unmodifiableList(out);
                }
                expect(',');
            }
        }

        private String string() throws IOException {
            expect('"');
            final StringBuilder out = new StringBuilder();
            while (!end()) {
                final char item = input.charAt(offset++);
                if (item == '"') {
                    if (out.length() > MAX_STRING_CHARS) {
                        throw error("JSON string exceeds limit");
                    }
                    return out.toString();
                }
                if (item == '\\') {
                    if (end()) {
                        throw error("unterminated JSON escape");
                    }
                    final char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> out.append(unicode());
                        default -> throw error("invalid JSON escape: " + escaped);
                    }
                } else if (item < 0x20) {
                    throw error("unescaped control character in JSON string");
                } else {
                    out.append(item);
                }
                if (out.length() > MAX_STRING_CHARS) {
                    throw error("JSON string exceeds limit");
                }
            }
            throw error("unterminated JSON string");
        }

        private char unicode() throws IOException {
            if (offset + 4 > input.length()) {
                throw error("invalid JSON unicode escape");
            }
            try {
                final char value = (char) Integer.parseInt(input.substring(offset, offset + 4), 16);
                offset += 4;
                return value;
            } catch (NumberFormatException ex) {
                throw error("invalid JSON unicode escape");
            }
        }

        private Object literal(final String text, final Object value) throws IOException {
            if (!input.startsWith(text, offset)) {
                throw error("invalid JSON literal");
            }
            offset += text.length();
            return value;
        }

        private Long number() throws IOException {
            final int start = offset;
            if (take('-') && end()) {
                throw error("invalid JSON number");
            }
            if (take('0')) {
                if (!end() && Character.isDigit(input.charAt(offset))) {
                    throw error("leading zero in JSON number");
                }
            } else {
                final int digits = offset;
                while (!end() && Character.isDigit(input.charAt(offset))) {
                    offset++;
                }
                if (digits == offset) {
                    throw error("invalid JSON number");
                }
            }
            if (!end() && (input.charAt(offset) == '.' || input.charAt(offset) == 'e'
                    || input.charAt(offset) == 'E')) {
                throw error("only integer JSON numbers are supported");
            }
            try {
                return Long.valueOf(input.substring(start, offset));
            } catch (NumberFormatException ex) {
                throw error("JSON integer is out of range");
            }
        }

        void whitespace() {
            while (!end() && Character.isWhitespace(input.charAt(offset))) {
                offset++;
            }
        }

        boolean end() {
            return offset >= input.length();
        }

        private boolean take(final char expected) {
            if (!end() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(final char expected) throws IOException {
            if (!take(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        IOException error(final String message) {
            return new IOException(message + " at offset " + offset);
        }
    }
}
