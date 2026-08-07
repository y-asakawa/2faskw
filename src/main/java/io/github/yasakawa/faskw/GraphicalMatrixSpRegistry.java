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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixSpRegistry {
    private static final int MAX_FILE_BYTES = 2_097_152;

    record Entry(String name, String entityId, String status, String source, String metadataSha256,
                 String metadataFile, List<String> certificateFingerprints, List<String> acsUrls,
                 String attributeProfile, String mfaProfile, List<String> cidrs,
                 String createdAt, String updatedAt, int currentRevision,
                 String legacyProviderId, String legacyMetadataFile, String legacyProviderXml,
                 String legacyAttributeXml, String legacyMfaProfile,
                 int attributeProfileRevision, boolean accessPolicyEnabled,
                 int accessPolicyRevision) {

        Entry {
            certificateFingerprints = List.copyOf(certificateFingerprints);
            acsUrls = List.copyOf(acsUrls);
            cidrs = List.copyOf(cidrs);
            if (attributeProfileRevision < 0 || accessPolicyRevision < 0) {
                throw new IllegalArgumentException("registry revisions must not be negative");
            }
        }

        Entry(final String name, final String entityId, final String status, final String source,
                final String metadataSha256, final String metadataFile,
                final List<String> certificateFingerprints, final List<String> acsUrls,
                final String attributeProfile, final String mfaProfile, final List<String> cidrs,
                final String createdAt, final String updatedAt, final int currentRevision,
                final String legacyProviderId, final String legacyMetadataFile,
                final String legacyProviderXml, final String legacyAttributeXml,
                final String legacyMfaProfile) {
            this(name, entityId, status, source, metadataSha256, metadataFile,
                certificateFingerprints, acsUrls, attributeProfile, mfaProfile, cidrs,
                createdAt, updatedAt, currentRevision, legacyProviderId, legacyMetadataFile,
                legacyProviderXml, legacyAttributeXml, legacyMfaProfile, 0, false, 0);
        }

        Entry withStatus(final String value, final Instant now) {
            return copy(value, source, metadataSha256, metadataFile, certificateFingerprints, acsUrls,
                attributeProfile, mfaProfile, cidrs, now, currentRevision,
                attributeProfileRevision, accessPolicyEnabled, accessPolicyRevision);
        }

        Entry withMetadata(final GraphicalMatrixSpMetadata.Parsed metadata, final String file,
                final String attributes, final String mfa, final List<String> newCidrs,
                final Instant now) {
            return copy(status, metadata.source(), metadata.sha256(), file,
                metadata.certificateFingerprints(), metadata.acsUrls(), attributes, mfa,
                newCidrs, now, currentRevision, attributeProfileRevision,
                accessPolicyEnabled, accessPolicyRevision);
        }

        Entry withPolicies(final String attributes, final String mfa, final List<String> newCidrs,
                final Instant now) {
            return copy(status, source, metadataSha256, metadataFile, certificateFingerprints,
                acsUrls, attributes, mfa, newCidrs, now, currentRevision,
                attributeProfileRevision, accessPolicyEnabled, accessPolicyRevision);
        }

        Entry withAttributeProfileRevision(final int revision, final Instant now) {
            return copy(status, source, metadataSha256, metadataFile, certificateFingerprints,
                acsUrls, attributeProfile, mfaProfile, cidrs, now, currentRevision,
                revision, accessPolicyEnabled, accessPolicyRevision);
        }

        Entry withAccessPolicy(final boolean enabled, final int revision, final Instant now) {
            return copy(status, source, metadataSha256, metadataFile, certificateFingerprints,
                acsUrls, attributeProfile, mfaProfile, cidrs, now, currentRevision,
                attributeProfileRevision, enabled, revision);
        }

        Entry withRevision(final int revision, final Instant now) {
            return copy(status, source, metadataSha256, metadataFile, certificateFingerprints,
                acsUrls, attributeProfile, mfaProfile, cidrs, now, revision,
                attributeProfileRevision, accessPolicyEnabled, accessPolicyRevision);
        }

        private Entry copy(final String newStatus, final String newSource, final String newSha,
                final String newFile, final List<String> newFingerprints, final List<String> newAcs,
                final String newAttributes, final String newMfa, final List<String> newCidrs,
                final Instant now, final int revision, final int profileRevision,
                final boolean accessEnabled, final int accessRevision) {
            return new Entry(name, entityId, newStatus, newSource, newSha, newFile,
                newFingerprints, newAcs, newAttributes, newMfa, newCidrs, createdAt,
                now.toString(), revision, legacyProviderId, legacyMetadataFile,
                legacyProviderXml, legacyAttributeXml, legacyMfaProfile, profileRevision,
                accessEnabled, accessRevision);
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
        final Map<String, Object> root = GraphicalMatrixJson.readObject(path, MAX_FILE_BYTES);
        GraphicalMatrixJson.rejectUnknown(root, "SP registry root", "schemaVersion", "entries");
        final int schema = GraphicalMatrixJson.integer(root, "schemaVersion", 0);
        if (schema != 1 && schema != 2) {
            throw new IOException("unsupported or missing SP registry schemaVersion");
        }
        final Map<String, Entry> values = new LinkedHashMap<>();
        for (final Object value : GraphicalMatrixJson.array(root, "entries", true)) {
            final Entry entry = entry(GraphicalMatrixJson.asObject(value, "SP registry entry"), schema);
            if (values.putIfAbsent(entry.name(), entry) != null) {
                throw new IOException("duplicate SP name in registry: " + entry.name());
            }
        }
        return new GraphicalMatrixSpRegistry(values);
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
        return entries.values().stream().filter(item -> item.entityId().equals(entityId))
            .findFirst().orElse(null);
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
        GraphicalMatrixSpFiles.atomicWrite(path, toJson(entries()));
    }

    static void saveEntry(final Path path, final Entry entry) throws IOException {
        GraphicalMatrixSpFiles.atomicWrite(path, toJson(List.of(entry)));
    }

    static Entry loadEntry(final Path path) throws IOException {
        final GraphicalMatrixSpRegistry registry = load(path);
        if (registry.entries.size() != 1) {
            throw new IOException("revision registry must contain exactly one SP entry");
        }
        return registry.entries.values().iterator().next();
    }

    static String toJson(final List<Entry> values) {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2L);
        final List<Object> records = new ArrayList<>();
        values.stream().sorted(Comparator.comparing(Entry::name))
            .forEach(entry -> records.add(object(entry)));
        root.put("entries", records);
        return GraphicalMatrixJson.write(root);
    }

    private static Entry entry(final Map<String, Object> value, final int schema) throws IOException {
        GraphicalMatrixJson.rejectUnknown(value, "SP registry entry",
            "name", "entityId", "status", "source", "metadataSha256", "metadataFile",
            "certificateFingerprints", "acsUrls", "attributeProfile", "mfaProfile", "cidrs",
            "createdAt", "updatedAt", "currentRevision", "legacyProviderId",
            "legacyMetadataFile", "legacyProviderXml", "legacyAttributeXml", "legacyMfaProfile",
            "attributeProfileRevision", "accessPolicyEnabled", "accessPolicyRevision");
        try {
            return new Entry(
                GraphicalMatrixJson.string(value, "name", true),
                GraphicalMatrixJson.string(value, "entityId", true),
                GraphicalMatrixJson.string(value, "status", true),
                GraphicalMatrixJson.string(value, "source", true),
                GraphicalMatrixJson.string(value, "metadataSha256", true),
                GraphicalMatrixJson.string(value, "metadataFile", true),
                GraphicalMatrixJson.strings(value, "certificateFingerprints"),
                GraphicalMatrixJson.strings(value, "acsUrls"),
                GraphicalMatrixJson.string(value, "attributeProfile", true),
                GraphicalMatrixJson.string(value, "mfaProfile", true),
                GraphicalMatrixJson.strings(value, "cidrs"),
                GraphicalMatrixJson.string(value, "createdAt", true),
                GraphicalMatrixJson.string(value, "updatedAt", true),
                GraphicalMatrixJson.integer(value, "currentRevision", 0),
                GraphicalMatrixJson.string(value, "legacyProviderId", false),
                GraphicalMatrixJson.string(value, "legacyMetadataFile", false),
                GraphicalMatrixJson.string(value, "legacyProviderXml", false),
                GraphicalMatrixJson.string(value, "legacyAttributeXml", false),
                GraphicalMatrixJson.string(value, "legacyMfaProfile", false),
                schema == 1 ? 0 : GraphicalMatrixJson.integer(
                    value, "attributeProfileRevision", 0),
                schema != 1 && GraphicalMatrixJson.bool(value, "accessPolicyEnabled", false),
                schema == 1 ? 0 : GraphicalMatrixJson.integer(
                    value, "accessPolicyRevision", 0));
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid SP registry entry: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> object(final Entry entry) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", entry.name());
        out.put("entityId", entry.entityId());
        out.put("status", entry.status());
        out.put("source", entry.source());
        out.put("metadataSha256", entry.metadataSha256());
        out.put("metadataFile", entry.metadataFile());
        out.put("certificateFingerprints", entry.certificateFingerprints());
        out.put("acsUrls", entry.acsUrls());
        out.put("attributeProfile", entry.attributeProfile());
        out.put("attributeProfileRevision", (long) entry.attributeProfileRevision());
        out.put("mfaProfile", entry.mfaProfile());
        out.put("cidrs", entry.cidrs());
        out.put("accessPolicyEnabled", entry.accessPolicyEnabled());
        out.put("accessPolicyRevision", (long) entry.accessPolicyRevision());
        out.put("createdAt", entry.createdAt());
        out.put("updatedAt", entry.updatedAt());
        out.put("currentRevision", (long) entry.currentRevision());
        out.put("legacyProviderId", entry.legacyProviderId());
        out.put("legacyMetadataFile", entry.legacyMetadataFile());
        out.put("legacyProviderXml", entry.legacyProviderXml());
        out.put("legacyAttributeXml", entry.legacyAttributeXml());
        out.put("legacyMfaProfile", entry.legacyMfaProfile());
        return out;
    }
}
