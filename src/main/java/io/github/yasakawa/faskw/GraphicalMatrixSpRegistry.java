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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class GraphicalMatrixSpRegistry {
    record Entry(String name, String entityId, String status, String source, String metadataSha256,
                 String metadataFile, List<String> certificateFingerprints, List<String> acsUrls,
                 String attributeProfile, String mfaProfile, List<String> cidrs,
                 String createdAt, String updatedAt, int currentRevision,
                 String legacyProviderId, String legacyMetadataFile, String legacyProviderXml,
                 String legacyAttributeXml, String legacyMfaProfile) {

        Entry {
            certificateFingerprints = List.copyOf(certificateFingerprints);
            acsUrls = List.copyOf(acsUrls);
            cidrs = List.copyOf(cidrs);
        }

        Entry withStatus(final String value, final Instant now) {
            return copy(value, source, metadataSha256, metadataFile, certificateFingerprints, acsUrls,
                attributeProfile, mfaProfile, cidrs, now, currentRevision);
        }

        Entry withMetadata(final GraphicalMatrixSpMetadata.Parsed metadata, final String file,
                final String attributes, final String mfa, final List<String> newCidrs,
                final Instant now) {
            return copy(status, metadata.source(), metadata.sha256(), file,
                metadata.certificateFingerprints(), metadata.acsUrls(), attributes, mfa,
                newCidrs, now, currentRevision);
        }

        Entry withPolicies(final String attributes, final String mfa, final List<String> newCidrs,
                final Instant now) {
            return copy(status, source, metadataSha256, metadataFile, certificateFingerprints,
                acsUrls, attributes, mfa, newCidrs, now, currentRevision);
        }

        Entry withRevision(final int revision, final Instant now) {
            return copy(status, source, metadataSha256, metadataFile, certificateFingerprints,
                acsUrls, attributeProfile, mfaProfile, cidrs, now, revision);
        }

        private Entry copy(final String newStatus, final String newSource, final String newSha,
                final String newFile, final List<String> newFingerprints, final List<String> newAcs,
                final String newAttributes, final String newMfa, final List<String> newCidrs,
                final Instant now, final int revision) {
            return new Entry(name, entityId, newStatus, newSource, newSha, newFile,
                newFingerprints, newAcs, newAttributes, newMfa, newCidrs, createdAt,
                now.toString(), revision, legacyProviderId, legacyMetadataFile,
                legacyProviderXml, legacyAttributeXml, legacyMfaProfile);
        }
    }

    private final LinkedHashMap<String, Entry> entries;

    private GraphicalMatrixSpRegistry(final Map<String, Entry> values) {
        entries = new LinkedHashMap<>(values);
    }

    static GraphicalMatrixSpRegistry empty() {
        return new GraphicalMatrixSpRegistry(Map.of());
    }

    static GraphicalMatrixSpRegistry load(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        final String json = Files.readString(path, StandardCharsets.UTF_8);
        if (!hasSchemaVersionOne(json)) {
            throw new IOException("unsupported or missing SP registry schemaVersion");
        }
        final LinkedHashMap<String, Entry> values = new LinkedHashMap<>();
        for (final String object : entryObjects(json)) {
            final Map<String, String> fields = fields(object);
            if (!fields.containsKey("name")) {
                continue;
            }
            final Entry entry = entry(fields);
            if (values.putIfAbsent(entry.name(), entry) != null) {
                throw new IOException("duplicate SP name in registry: " + entry.name());
            }
        }
        return new GraphicalMatrixSpRegistry(values);
    }

    private static List<String> entryObjects(final String json) throws IOException {
        final int entriesKey = json.indexOf("\"entries\"");
        final int arrayStart = entriesKey < 0 ? -1 : json.indexOf('[', entriesKey);
        if (arrayStart < 0) {
            throw new IOException("SP registry entries array is missing");
        }
        final List<String> objects = new ArrayList<>();
        boolean string = false;
        boolean escaped = false;
        int depth = 0;
        int objectStart = -1;
        for (int i = arrayStart + 1; i < json.length(); i++) {
            final char item = json.charAt(i);
            if (string) {
                if (escaped) {
                    escaped = false;
                } else if (item == '\\') {
                    escaped = true;
                } else if (item == '"') {
                    string = false;
                }
                continue;
            }
            if (item == '"') {
                string = true;
                continue;
            }
            if (item == '{') {
                if (depth++ == 0) {
                    objectStart = i + 1;
                }
                continue;
            }
            if (item == '}') {
                if (depth <= 0) {
                    throw new IOException("unexpected object terminator in SP registry");
                }
                if (--depth == 0) {
                    objects.add(json.substring(objectStart, i));
                    objectStart = -1;
                }
                continue;
            }
            if (item == ']' && depth == 0) {
                return List.copyOf(objects);
            }
            if (depth == 0 && !Character.isWhitespace(item) && item != ',') {
                throw new IOException("unexpected content in SP registry entries array");
            }
        }
        throw new IOException("unterminated SP registry entries array");
    }

    GraphicalMatrixSpRegistry copy() {
        return new GraphicalMatrixSpRegistry(entries);
    }

    List<Entry> entries() {
        return entries.values().stream().sorted(Comparator.comparing(Entry::name)).toList();
    }

    Entry get(final String name) {
        final Entry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("SP is not managed: " + name);
        }
        return entry;
    }

    Entry findByEntityId(final String entityId) {
        for (final Entry entry : entries.values()) {
            if (entry.entityId().equals(entityId)) {
                return entry;
            }
        }
        return null;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    void put(final Entry entry) {
        final Entry sameEntity = findByEntityId(entry.entityId());
        if (sameEntity != null && !sameEntity.name().equals(entry.name())) {
            throw new IllegalArgumentException("entityID is already managed as " + sameEntity.name());
        }
        entries.put(entry.name(), entry);
    }

    Entry remove(final String name) {
        final Entry removed = entries.remove(name);
        if (removed == null) {
            throw new IllegalArgumentException("SP is not managed: " + name);
        }
        return removed;
    }

    void save(final Path path) throws IOException {
        atomicWrite(path, toJson(entries()));
    }

    static void saveEntry(final Path path, final Entry entry) throws IOException {
        atomicWrite(path, toJson(List.of(entry)));
    }

    static Entry loadEntry(final Path path) throws IOException {
        final GraphicalMatrixSpRegistry registry = load(path);
        if (registry.entries.size() != 1) {
            throw new IOException("revision registry must contain exactly one SP entry");
        }
        return registry.entries.values().iterator().next();
    }

    static String toJson(final List<Entry> values) {
        final StringBuilder out = new StringBuilder(1024);
        out.append("{\n  \"schemaVersion\": 1,\n  \"entries\": [\n");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            appendEntry(out, values.get(i));
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static void appendEntry(final StringBuilder out, final Entry entry) {
        out.append("    {");
        string(out, "name", entry.name(), true);
        string(out, "entityId", entry.entityId(), false);
        string(out, "status", entry.status(), false);
        string(out, "source", entry.source(), false);
        string(out, "metadataSha256", entry.metadataSha256(), false);
        string(out, "metadataFile", entry.metadataFile(), false);
        array(out, "certificateFingerprints", entry.certificateFingerprints());
        array(out, "acsUrls", entry.acsUrls());
        string(out, "attributeProfile", entry.attributeProfile(), false);
        string(out, "mfaProfile", entry.mfaProfile(), false);
        array(out, "cidrs", entry.cidrs());
        string(out, "createdAt", entry.createdAt(), false);
        string(out, "updatedAt", entry.updatedAt(), false);
        out.append(",\"currentRevision\":").append(entry.currentRevision());
        string(out, "legacyProviderId", entry.legacyProviderId(), false);
        string(out, "legacyMetadataFile", entry.legacyMetadataFile(), false);
        string(out, "legacyProviderXml", entry.legacyProviderXml(), false);
        string(out, "legacyAttributeXml", entry.legacyAttributeXml(), false);
        string(out, "legacyMfaProfile", entry.legacyMfaProfile(), false);
        out.append('}');
    }

    private static Entry entry(final Map<String, String> fields) throws IOException {
        try {
            return new Entry(required(fields, "name"), required(fields, "entityId"),
                required(fields, "status"), required(fields, "source"),
                required(fields, "metadataSha256"), required(fields, "metadataFile"),
                arrayValue(fields.get("certificateFingerprints")),
                arrayValue(fields.get("acsUrls")), required(fields, "attributeProfile"),
                required(fields, "mfaProfile"), arrayValue(fields.get("cidrs")),
                required(fields, "createdAt"), required(fields, "updatedAt"),
                Integer.parseInt(required(fields, "currentRevision")),
                value(fields, "legacyProviderId"), value(fields, "legacyMetadataFile"),
                value(fields, "legacyProviderXml"), value(fields, "legacyAttributeXml"),
                value(fields, "legacyMfaProfile"));
        } catch (RuntimeException ex) {
            throw new IOException("invalid SP registry entry: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, String> fields(final String object) throws IOException {
        final Map<String, String> out = new LinkedHashMap<>();
        int offset = skipWhitespace(object, 0);
        while (offset < object.length()) {
            if (object.charAt(offset) != '"') {
                throw new IOException("expected JSON field name in SP registry");
            }
            final int keyEnd = stringEnd(object, offset);
            final String key = unescape(object.substring(offset + 1, keyEnd - 1));
            offset = skipWhitespace(object, keyEnd);
            if (offset >= object.length() || object.charAt(offset) != ':') {
                throw new IOException("expected ':' after JSON field name: " + key);
            }
            offset = skipWhitespace(object, offset + 1);
            final int valueStart = offset;
            offset = valueEnd(object, offset);
            if (out.putIfAbsent(key, object.substring(valueStart, offset)) != null) {
                throw new IOException("duplicate field in SP registry: " + key);
            }
            offset = skipWhitespace(object, offset);
            if (offset == object.length()) {
                break;
            }
            if (object.charAt(offset) != ',') {
                throw new IOException("expected ',' after JSON field: " + key);
            }
            offset = skipWhitespace(object, offset + 1);
        }
        return out;
    }

    private static boolean hasSchemaVersionOne(final String json) {
        final int key = json.indexOf("\"schemaVersion\"");
        if (key < 0) {
            return false;
        }
        int offset = skipWhitespace(json, key + "\"schemaVersion\"".length());
        if (offset >= json.length() || json.charAt(offset) != ':') {
            return false;
        }
        offset = skipWhitespace(json, offset + 1);
        if (offset >= json.length() || json.charAt(offset) != '1') {
            return false;
        }
        offset = skipWhitespace(json, offset + 1);
        return offset == json.length() || json.charAt(offset) == ',' || json.charAt(offset) == '}';
    }

    private static int valueEnd(final String value, final int offset) throws IOException {
        if (offset >= value.length()) {
            throw new IOException("missing JSON field value in SP registry");
        }
        return switch (value.charAt(offset)) {
            case '"' -> stringEnd(value, offset);
            case '[' -> arrayEnd(value, offset);
            case 't' -> literalEnd(value, offset, "true");
            case 'f' -> literalEnd(value, offset, "false");
            case 'n' -> literalEnd(value, offset, "null");
            default -> numberEnd(value, offset);
        };
    }

    private static int arrayEnd(final String value, final int start) throws IOException {
        int offset = skipWhitespace(value, start + 1);
        if (offset < value.length() && value.charAt(offset) == ']') {
            return offset + 1;
        }
        while (true) {
            if (offset >= value.length() || value.charAt(offset) != '"') {
                throw new IOException("SP registry arrays must contain JSON strings");
            }
            offset = skipWhitespace(value, stringEnd(value, offset));
            if (offset >= value.length()) {
                throw new IOException("unterminated JSON array in SP registry");
            }
            if (value.charAt(offset) == ']') {
                return offset + 1;
            }
            if (value.charAt(offset) != ',') {
                throw new IOException("expected ',' in JSON array in SP registry");
            }
            offset = skipWhitespace(value, offset + 1);
        }
    }

    private static int literalEnd(final String value, final int start, final String literal)
            throws IOException {
        if (!value.startsWith(literal, start)) {
            throw new IOException("invalid JSON literal in SP registry");
        }
        return start + literal.length();
    }

    private static int numberEnd(final String value, final int start) throws IOException {
        int offset = value.charAt(start) == '-' ? start + 1 : start;
        final int digits = offset;
        while (offset < value.length() && Character.isDigit(value.charAt(offset))) {
            offset++;
        }
        if (digits == offset) {
            throw new IOException("invalid JSON number in SP registry");
        }
        return offset;
    }

    private static int stringEnd(final String value, final int start) throws IOException {
        for (int offset = start + 1; offset < value.length(); offset++) {
            final char item = value.charAt(offset);
            if (item == '"') {
                return offset + 1;
            }
            if (item == '\\') {
                offset++;
                if (offset >= value.length()) {
                    break;
                }
                continue;
            }
            if (item < 0x20) {
                throw new IOException("unescaped control character in JSON string");
            }
        }
        throw new IOException("unterminated JSON string in SP registry");
    }

    private static int skipWhitespace(final String value, final int offset) {
        int current = offset;
        while (current < value.length() && Character.isWhitespace(value.charAt(current))) {
            current++;
        }
        return current;
    }

    private static String required(final Map<String, String> fields, final String key) {
        final String value = value(fields, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing registry field: " + key);
        }
        return value;
    }

    private static String value(final Map<String, String> fields, final String key) {
        final String raw = fields.get(key);
        if (raw == null || "null".equals(raw)) {
            return "";
        }
        return raw.startsWith("\"") ? unescape(raw.substring(1, raw.length() - 1)) : raw;
    }

    private static List<String> arrayValue(final String raw) {
        if (raw == null || "null".equals(raw)) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        int offset = skipWhitespace(raw, 0);
        if (offset >= raw.length() || raw.charAt(offset) != '[') {
            throw new IllegalArgumentException("expected JSON string array");
        }
        offset = skipWhitespace(raw, offset + 1);
        if (offset < raw.length() && raw.charAt(offset) == ']') {
            return List.of();
        }
        while (offset < raw.length()) {
            if (raw.charAt(offset) != '"') {
                throw new IllegalArgumentException("expected JSON string array item");
            }
            final int end;
            try {
                end = stringEnd(raw, offset);
            } catch (IOException ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
            out.add(unescape(raw.substring(offset + 1, end - 1)));
            offset = skipWhitespace(raw, end);
            if (offset < raw.length() && raw.charAt(offset) == ']') {
                offset = skipWhitespace(raw, offset + 1);
                if (offset != raw.length()) {
                    throw new IllegalArgumentException("unexpected content after JSON array");
                }
                return List.copyOf(out);
            }
            if (offset >= raw.length() || raw.charAt(offset) != ',') {
                throw new IllegalArgumentException("expected ',' in JSON string array");
            }
            offset = skipWhitespace(raw, offset + 1);
        }
        throw new IllegalArgumentException("unterminated JSON string array");
    }

    private static void string(final StringBuilder out, final String key, final String value,
            final boolean first) {
        if (!first) {
            out.append(',');
        }
        out.append('\"').append(key).append("\":\"").append(escape(value)).append('\"');
    }

    private static void array(final StringBuilder out, final String key, final List<String> values) {
        out.append(',').append('\"').append(key).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('\"').append(escape(values.get(i))).append('\"');
        }
        out.append(']');
    }

    private static String escape(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char item = value.charAt(i);
            switch (item) {
                case '\\' -> out.append("\\\\");
                case '\"' -> out.append("\\\"");
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
        return out.toString();
    }

    private static String unescape(final String value) {
        final StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char item = value.charAt(i);
            if (item != '\\') {
                out.append(item);
                continue;
            }
            if (++i >= value.length()) {
                throw new IllegalArgumentException("invalid trailing JSON escape");
            }
            final char escaped = value.charAt(i);
            switch (escaped) {
                case '\\', '\"', '/' -> out.append(escaped);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        throw new IllegalArgumentException("invalid JSON unicode escape");
                    }
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("invalid JSON escape: " + escaped);
            }
        }
        return out.toString();
    }

    private static void atomicWrite(final Path path, final String content) throws IOException {
        GraphicalMatrixSpFiles.atomicWrite(path, content);
    }
}
