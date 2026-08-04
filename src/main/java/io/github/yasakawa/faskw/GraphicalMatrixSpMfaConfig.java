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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixSpMfaConfig {
    private static final String FORCE = "graphicalmatrix.mfa.forceSPs";
    private static final String BYPASS = "graphicalmatrix.mfa.bypassSPs";
    private static final String REQUIRED = "graphicalmatrix.mfa.requiredSPs";
    private static final String SP_CIDR = "graphicalmatrix.mfa.bypassSpCidrs";
    private static final List<String> MANAGED_KEYS = List.of(FORCE, BYPASS, REQUIRED, SP_CIDR);

    private GraphicalMatrixSpMfaConfig() {
    }

    static String inferProfile(final Path path, final String entityId) throws IOException {
        final Parsed parsed = parse(path);
        final List<String> matches = new ArrayList<>();
        if (csv(parsed.values().get(FORCE)).contains(entityId)) {
            matches.add("force");
        }
        if (csv(parsed.values().get(BYPASS)).contains(entityId)) {
            matches.add("bypass");
        }
        if (csv(parsed.values().get(REQUIRED)).contains(entityId)) {
            matches.add("required");
        }
        if (spCidrs(parsed.values().get(SP_CIDR)).containsKey(entityId)) {
            matches.add("sp-cidr-bypass");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("entityID appears in multiple MFA policy lists: " + matches);
        }
        return matches.isEmpty() ? "inherit" : matches.get(0);
    }

    static List<String> inferCidrs(final Path path, final String entityId) throws IOException {
        return spCidrs(parse(path).values().get(SP_CIDR)).getOrDefault(entityId, List.of());
    }

    static void render(final Path path, final List<GraphicalMatrixSpRegistry.Entry> oldEntries,
            final List<GraphicalMatrixSpRegistry.Entry> newEntries,
            final GraphicalMatrixSpRegistry.Entry restoreLegacy) throws IOException {
        final Parsed parsed = parse(path);
        final Set<String> oldManaged = new LinkedHashSet<>();
        for (final GraphicalMatrixSpRegistry.Entry entry : oldEntries) {
            oldManaged.add(entry.entityId());
        }

        final LinkedHashSet<String> force = csv(parsed.values().get(FORCE));
        final LinkedHashSet<String> bypass = csv(parsed.values().get(BYPASS));
        final LinkedHashSet<String> required = csv(parsed.values().get(REQUIRED));
        final LinkedHashMap<String, List<String>> cidrs = spCidrs(parsed.values().get(SP_CIDR));
        force.removeAll(oldManaged);
        bypass.removeAll(oldManaged);
        required.removeAll(oldManaged);
        oldManaged.forEach(cidrs::remove);

        for (final GraphicalMatrixSpRegistry.Entry entry : newEntries) {
            if (!"ACTIVE".equals(entry.status())) {
                continue;
            }
            addProfile(entry.mfaProfile(), entry.entityId(), entry.cidrs(), force, bypass, required, cidrs);
        }
        if (restoreLegacy != null) {
            addProfile(restoreLegacy.legacyMfaProfile(), restoreLegacy.entityId(), restoreLegacy.cidrs(),
                force, bypass, required, cidrs);
        }

        final Map<String, String> replacements = Map.of(
            FORCE, String.join(",", force),
            BYPASS, String.join(",", bypass),
            REQUIRED, String.join(",", required),
            SP_CIDR, formatSpCidrs(cidrs));
        final List<String> output = new ArrayList<>();
        final Set<String> replaced = new LinkedHashSet<>();
        for (final String line : parsed.lines()) {
            final String key = propertyKey(line);
            if (MANAGED_KEYS.contains(key)) {
                output.add(key + " = " + replacements.get(key));
                replaced.add(key);
            } else {
                output.add(line);
            }
        }
        for (final String key : MANAGED_KEYS) {
            if (!replaced.contains(key)) {
                output.add(key + " = " + replacements.get(key));
            }
        }
        atomicWrite(path, String.join("\n", output) + "\n");
    }

    static void validateProfile(final String profile, final List<String> cidrs) {
        if (!Set.of("inherit", "force", "bypass", "sp-cidr-bypass", "required").contains(profile)) {
            throw new IllegalArgumentException("unknown MFA profile: " + profile);
        }
        if ("sp-cidr-bypass".equals(profile)) {
            if (cidrs.isEmpty()) {
                throw new IllegalArgumentException("sp-cidr-bypass requires --cidrs");
            }
            for (final String cidr : cidrs) {
                validateIpv4Cidr(cidr);
            }
        } else if (!cidrs.isEmpty()) {
            throw new IllegalArgumentException("--cidrs is valid only with sp-cidr-bypass");
        }
    }

    private static void addProfile(final String profile, final String entityId,
            final List<String> entryCidrs, final Set<String> force, final Set<String> bypass,
            final Set<String> required, final Map<String, List<String>> cidrs) {
        switch (profile == null || profile.isBlank() ? "inherit" : profile) {
            case "inherit" -> {
            }
            case "force" -> force.add(entityId);
            case "bypass" -> bypass.add(entityId);
            case "required" -> required.add(entityId);
            case "sp-cidr-bypass" -> cidrs.put(entityId, List.copyOf(entryCidrs));
            default -> throw new IllegalArgumentException("unknown MFA profile: " + profile);
        }
    }

    private static Parsed parse(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("MFA policy file is missing: " + path);
        }
        final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String line : lines) {
            final String key = propertyKey(line);
            if (!MANAGED_KEYS.contains(key)) {
                continue;
            }
            if (values.putIfAbsent(key, propertyValue(line)) != null) {
                throw new IllegalArgumentException("duplicate MFA property: " + key);
            }
        }
        return new Parsed(List.copyOf(lines), Map.copyOf(values));
    }

    private static String propertyKey(final String line) {
        final String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return "";
        }
        final int separator = trimmed.indexOf('=');
        return separator < 0 ? "" : trimmed.substring(0, separator).trim();
    }

    private static String propertyValue(final String line) {
        final int separator = line.indexOf('=');
        return separator < 0 ? "" : line.substring(separator + 1).trim();
    }

    private static LinkedHashSet<String> csv(final String value) {
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null) {
            return out;
        }
        for (final String raw : value.split(",", -1)) {
            final String item = raw.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private static LinkedHashMap<String, List<String>> spCidrs(final String value) {
        final LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (final String rawRule : value.split(";", -1)) {
            final String rule = rawRule.trim();
            final int separator = rule.indexOf('|');
            if (separator <= 0 || separator != rule.lastIndexOf('|')) {
                throw new IllegalArgumentException("invalid bypassSpCidrs rule: " + rule);
            }
            final String entityId = rule.substring(0, separator).trim();
            final List<String> ranges = new ArrayList<>(csv(rule.substring(separator + 1)));
            if (entityId.isEmpty() || ranges.isEmpty()) {
                throw new IllegalArgumentException("invalid bypassSpCidrs rule: " + rule);
            }
            ranges.forEach(GraphicalMatrixSpMfaConfig::validateIpv4Cidr);
            if (out.putIfAbsent(entityId, List.copyOf(ranges)) != null) {
                throw new IllegalArgumentException("duplicate bypassSpCidrs entityID: " + entityId);
            }
        }
        return out;
    }

    private static String formatSpCidrs(final Map<String, List<String>> values) {
        final List<String> rules = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : values.entrySet()) {
            rules.add(entry.getKey() + "|" + String.join(",", entry.getValue()));
        }
        return String.join(";", rules);
    }

    private static void validateIpv4Cidr(final String cidr) {
        final String[] parts = cidr.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid IPv4 CIDR: " + cidr);
        }
        final String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("invalid IPv4 CIDR: " + cidr);
        }
        try {
            for (final String octet : octets) {
                if (octet.isEmpty() || Integer.parseInt(octet) < 0 || Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException("invalid IPv4 CIDR: " + cidr);
                }
            }
            final int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("invalid IPv4 CIDR: " + cidr);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid IPv4 CIDR: " + cidr, ex);
        }
    }

    private static void atomicWrite(final Path path, final String content) throws IOException {
        GraphicalMatrixSpFiles.atomicWrite(path, content);
    }

    private record Parsed(List<String> lines, Map<String, String> values) {
    }
}
