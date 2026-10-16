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

package io.github.yasakawa.faskw.spimport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded JSON trees only; no class loading, polymorphism or permissive field coercion. */
final class StrictJson {
    private static final JsonFactory FACTORY = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    static Map<String, Object> parse(byte[] bytes) throws Exception {
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("JSON size limit");
        try (JsonParser parser = FACTORY.createParser(bytes)) {
            Object value = read(parser, parser.nextToken(), 0, new int[1]);
            if (parser.nextToken() != null) throw new IllegalArgumentException("trailing JSON input");
            return object(value);
        }
    }
    private static Object read(JsonParser p, JsonToken token, int depth, int[] nodes) throws Exception {
        if (depth > 32 || ++nodes[0] > 100000) throw new IllegalArgumentException("JSON resource limit");
        if (token == JsonToken.START_OBJECT) {
            Map<String, Object> result = new LinkedHashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME) throw new IllegalArgumentException("JSON field expected");
                String key = p.currentName();
                result.put(key, read(p, p.nextToken(), depth + 1, nodes));
            }
            return result;
        }
        if (token == JsonToken.START_ARRAY) {
            List<Object> result = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) result.add(read(p, p.currentToken(), depth + 1, nodes));
            return result;
        }
        if (token == JsonToken.VALUE_STRING) return p.getText();
        if (token == JsonToken.VALUE_NUMBER_INT) return p.getLongValue();
        if (token == JsonToken.VALUE_TRUE) return true;
        if (token == JsonToken.VALUE_FALSE) return false;
        throw new IllegalArgumentException("unsupported JSON value");
    }
    static byte[] write(Object value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var generator = FACTORY.createGenerator(out)) { writeValue(generator, value); }
        return out.toByteArray();
    }
    private static void writeValue(com.fasterxml.jackson.core.JsonGenerator g, Object value) throws Exception {
        if (value instanceof Map<?, ?> map) {
            g.writeStartObject();
            java.util.TreeMap<String, Object> ordered = new java.util.TreeMap<>();
            map.forEach((key, item) -> ordered.put((String) key, item));
            for (var entry : ordered.entrySet()) { g.writeFieldName(entry.getKey()); writeValue(g, entry.getValue()); }
            g.writeEndObject();
        } else if (value instanceof List<?> list) {
            g.writeStartArray(); for (Object item : list) writeValue(g, item); g.writeEndArray();
        } else if (value instanceof String s) g.writeString(s);
        else if (value instanceof Boolean b) g.writeBoolean(b);
        else if (value instanceof Number n) g.writeNumber(n.longValue());
        else throw new IllegalArgumentException("unsupported JSON output");
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON object required");
        return (Map<String, Object>) value;
    }
    static void fields(Map<String, Object> map, String... allowed) {
        Set<String> keys = Set.of(allowed);
        if (!keys.containsAll(map.keySet())) throw new IllegalArgumentException("unknown JSON fields: " + map.keySet().stream().filter(k -> !keys.contains(k)).toList());
    }
    static String string(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String s)) throw new IllegalArgumentException("string required: " + key);
        if (s.length() > 8192 || s.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("invalid text: " + key);
        return s;
    }
    static long number(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Long n) || n < 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException("integer required: " + key);
        return n;
    }
    static boolean bool(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Boolean b)) throw new IllegalArgumentException("boolean required: " + key);
        return b;
    }
    static List<?> array(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof List<?> list)) throw new IllegalArgumentException("array required: " + key);
        return list;
    }
    static List<String> strings(Map<String, Object> map, String key) {
        List<String> result = stringsWithDuplicates(map, key);
        if (result.size() != new java.util.HashSet<>(result).size()) throw new IllegalArgumentException("duplicate values: " + key);
        return result;
    }
    static List<String> stringsWithDuplicates(Map<String, Object> map, String key) {
        List<String> result = new ArrayList<>();
        for (Object v : array(map, key)) result.add(string(Map.of("value", v), "value"));
        return List.copyOf(result);
    }
    private StrictJson() { }
}
