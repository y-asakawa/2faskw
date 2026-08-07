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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixAttributeCatalog {
    static final int MAX_FILE_BYTES = 1_048_576;
    static final Set<String> GOVERNANCE = Set.of(
        "candidate", "approval-required", "release-approved", "internal-only", "blocked");
    static final Set<String> RESOLUTION = Set.of(
        "declared", "runtime-observed", "conditional", "unknown");
    static final Set<String> MAPPING = Set.of("mapped", "mapping-required", "unknown");
    static final Set<String> CLASSIFICATION = Set.of(
        "public", "personal", "internal", "restricted", "credential");

    private static final Set<String> BUILTIN_BLOCKED = Set.of(
        "userpassword", "authpassword", "unicodepwd", "sambantpassword", "pwdhistory",
        "password", "credential", "secret", "token", "totp", "webauthn",
        "graphicalmatrixsequence");

    record Attribute(String id, String resolution, String samlMapping, String governance,
                     String classification, String purpose, String updatedAt) {
        Attribute {
            GraphicalMatrixSpAccessPolicy.validateAttributeId(id);
            if (!RESOLUTION.contains(resolution)) {
                throw new IllegalArgumentException("invalid attribute resolution: " + resolution);
            }
            if (!MAPPING.contains(samlMapping)) {
                throw new IllegalArgumentException("invalid SAML mapping state: " + samlMapping);
            }
            if (!GOVERNANCE.contains(governance)) {
                throw new IllegalArgumentException("invalid attribute governance: " + governance);
            }
            if (!CLASSIFICATION.contains(classification)) {
                throw new IllegalArgumentException("invalid attribute classification: "
                    + classification);
            }
            if (purpose.length() > 512 || hasControl(purpose)) {
                throw new IllegalArgumentException("invalid attribute purpose");
            }
            Instant.parse(updatedAt);
        }

        boolean accessApproved() {
            return "internal-only".equals(governance) || "release-approved".equals(governance);
        }

        boolean releaseApproved() {
            return "release-approved".equals(governance) && "mapped".equals(samlMapping);
        }
    }

    record Profile(String name, List<String> attributes, String description, int revision,
                   String source, String updatedAt) {
        Profile {
            validateProfileName(name);
            if (attributes.isEmpty() || attributes.size() > 32) {
                throw new IllegalArgumentException("profile attribute count must be 1..32");
            }
            final Set<String> unique = new LinkedHashSet<>();
            for (final String attribute : attributes) {
                GraphicalMatrixSpAccessPolicy.validateAttributeId(attribute);
                if (!unique.add(attribute)) {
                    throw new IllegalArgumentException("duplicate attribute in profile: " + attribute);
                }
            }
            attributes = List.copyOf(unique);
            if (description.length() > 512 || hasControl(description)) {
                throw new IllegalArgumentException("invalid profile description");
            }
            if (revision < 1) {
                throw new IllegalArgumentException("profile revision must be positive");
            }
            if (!Set.of("managed", "builtin", "legacy-properties").contains(source)) {
                throw new IllegalArgumentException("invalid profile source: " + source);
            }
            Instant.parse(updatedAt);
        }
    }

    private final Map<String, Attribute> attributes;
    private final Map<String, Profile> profiles;

    private GraphicalMatrixAttributeCatalog(final Map<String, Attribute> attributeValues,
            final Map<String, Profile> profileValues) {
        attributes = Map.copyOf(attributeValues);
        profiles = Map.copyOf(profileValues);
    }

    static GraphicalMatrixAttributeCatalog defaults() {
        final String now = Instant.EPOCH.toString();
        final Map<String, Attribute> attributes = new LinkedHashMap<>();
        attributes.put("uid", new Attribute("uid", "declared", "mapped", "release-approved",
            "personal", "Built-in v1.2.x profile compatibility", now));
        attributes.put("mail", new Attribute("mail", "declared", "mapped", "release-approved",
            "personal", "Built-in v1.2.x profile compatibility", now));
        return new GraphicalMatrixAttributeCatalog(attributes, Map.of());
    }

    static GraphicalMatrixAttributeCatalog load(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return defaults();
        }
        final Map<String, Object> root = GraphicalMatrixJson.readObject(path, MAX_FILE_BYTES);
        GraphicalMatrixJson.rejectUnknown(root, "attribute catalog root",
            "schemaVersion", "attributes", "profiles");
        if (GraphicalMatrixJson.integer(root, "schemaVersion", 0) != 1) {
            throw new IOException("unsupported or missing attribute catalog schemaVersion");
        }
        final Map<String, Attribute> attributes = new LinkedHashMap<>();
        for (final Object raw : GraphicalMatrixJson.array(root, "attributes", true)) {
            final Attribute attribute = attribute(GraphicalMatrixJson.asObject(raw, "attribute"));
            if (attributes.putIfAbsent(attribute.id(), attribute) != null) {
                throw new IOException("duplicate attribute in catalog: " + attribute.id());
            }
        }
        final Map<String, Profile> profiles = new LinkedHashMap<>();
        for (final Object raw : GraphicalMatrixJson.array(root, "profiles", true)) {
            final Profile profile = profile(GraphicalMatrixJson.asObject(raw, "profile"));
            if (profiles.putIfAbsent(profile.name(), profile) != null) {
                throw new IOException("duplicate profile in catalog: " + profile.name());
            }
        }
        final GraphicalMatrixAttributeCatalog catalog =
            new GraphicalMatrixAttributeCatalog(attributes, profiles);
        for (final Profile profile : catalog.profiles()) {
            for (final String id : profile.attributes()) {
                final Attribute attribute = catalog.findAttribute(id);
                if (attribute == null || !attribute.releaseApproved()) {
                    throw new IOException("profile attribute is not release-approved and mapped: "
                        + profile.name() + ":" + id);
                }
            }
        }
        return catalog;
    }

    List<Attribute> attributes() {
        return attributes.values().stream().sorted(Comparator.comparing(Attribute::id)).toList();
    }

    List<Profile> profiles() {
        return profiles.values().stream().sorted(Comparator.comparing(Profile::name)).toList();
    }

    Attribute findAttribute(final String id) {
        return attributes.get(id);
    }

    Profile findProfile(final String name) {
        return profiles.get(name);
    }

    Attribute requireAttribute(final String id) {
        final Attribute value = findAttribute(id);
        if (value == null) {
            throw new IllegalArgumentException("attribute is not present in the catalog: " + id);
        }
        return value;
    }

    GraphicalMatrixAttributeCatalog withAttribute(final Attribute value) {
        final Map<String, Attribute> copy = new LinkedHashMap<>(attributes);
        copy.put(value.id(), value);
        return new GraphicalMatrixAttributeCatalog(copy, profiles);
    }

    GraphicalMatrixAttributeCatalog withProfile(final Profile value) {
        if (Set.of("none", "uid", "uid-mail").contains(value.name())) {
            throw new IllegalArgumentException("reserved attribute profile: " + value.name());
        }
        for (final String id : value.attributes()) {
            final Attribute attribute = requireAttribute(id);
            if (!attribute.releaseApproved()) {
                throw new IllegalArgumentException(
                    "attribute is not approved and mapped for release: " + id);
            }
        }
        final Map<String, Profile> copy = new LinkedHashMap<>(profiles);
        copy.put(value.name(), value);
        return new GraphicalMatrixAttributeCatalog(attributes, copy);
    }

    GraphicalMatrixAttributeCatalog withoutProfile(final String name) {
        final Map<String, Profile> copy = new LinkedHashMap<>(profiles);
        if (copy.remove(name) == null) {
            throw new IllegalArgumentException("managed attribute profile not found: " + name);
        }
        return new GraphicalMatrixAttributeCatalog(attributes, copy);
    }

    boolean isBlocked(final String id, final Set<String> organizationBlocked) {
        final String lower = id.toLowerCase(Locale.ROOT);
        if (organizationBlocked.stream().anyMatch(value -> value.equalsIgnoreCase(id))) {
            return true;
        }
        return BUILTIN_BLOCKED.stream().anyMatch(lower::contains);
    }

    Map<String, List<String>> managedProfiles() {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (final Profile profile : profiles()) {
            out.put(profile.name(), profile.attributes());
        }
        return Map.copyOf(out);
    }

    void save(final Path path) throws IOException {
        GraphicalMatrixSpFiles.atomicWrite(path, toJson());
    }

    String toJson() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1L);
        final List<Object> attributeValues = new ArrayList<>();
        for (final Attribute attribute : attributes()) {
            attributeValues.add(attributeObject(attribute));
        }
        root.put("attributes", attributeValues);
        final List<Object> profileValues = new ArrayList<>();
        for (final Profile profile : profiles()) {
            profileValues.add(profileObject(profile));
        }
        root.put("profiles", profileValues);
        return GraphicalMatrixJson.write(root);
    }

    private static Attribute attribute(final Map<String, Object> value) throws IOException {
        GraphicalMatrixJson.rejectUnknown(value, "catalog attribute", "id", "resolution",
            "samlMapping", "governance", "classification", "purpose", "updatedAt");
        try {
            return new Attribute(GraphicalMatrixJson.string(value, "id", true),
                GraphicalMatrixJson.string(value, "resolution", true),
                GraphicalMatrixJson.string(value, "samlMapping", true),
                GraphicalMatrixJson.string(value, "governance", true),
                GraphicalMatrixJson.string(value, "classification", true),
                GraphicalMatrixJson.string(value, "purpose", false),
                GraphicalMatrixJson.string(value, "updatedAt", true));
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid catalog attribute: " + ex.getMessage(), ex);
        }
    }

    private static Profile profile(final Map<String, Object> value) throws IOException {
        GraphicalMatrixJson.rejectUnknown(value, "catalog profile", "name", "attributes",
            "description", "revision", "source", "updatedAt");
        try {
            return new Profile(GraphicalMatrixJson.string(value, "name", true),
                GraphicalMatrixJson.strings(value, "attributes"),
                GraphicalMatrixJson.string(value, "description", false),
                GraphicalMatrixJson.integer(value, "revision", 0),
                GraphicalMatrixJson.string(value, "source", true),
                GraphicalMatrixJson.string(value, "updatedAt", true));
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid catalog profile: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> attributeObject(final Attribute value) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", value.id());
        out.put("resolution", value.resolution());
        out.put("samlMapping", value.samlMapping());
        out.put("governance", value.governance());
        out.put("classification", value.classification());
        out.put("purpose", value.purpose());
        out.put("updatedAt", value.updatedAt());
        return out;
    }

    private static Map<String, Object> profileObject(final Profile value) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", value.name());
        out.put("attributes", value.attributes());
        out.put("description", value.description());
        out.put("revision", (long) value.revision());
        out.put("source", value.source());
        out.put("updatedAt", value.updatedAt());
        return out;
    }

    static void validateProfileName(final String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("profile name must match [a-z][a-z0-9-]{0,31}");
        }
    }

    private static boolean hasControl(final String value) {
        return value.chars().anyMatch(item -> item < 0x20 || item == 0x7f);
    }
}
