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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class GraphicalMatrixAttributeDiscovery {
    record Candidate(String id, String resolution, String samlMapping, String governance,
                     Set<String> usedBy) {
        Candidate {
            usedBy = Set.copyOf(usedBy);
        }
    }

    private GraphicalMatrixAttributeDiscovery() {
    }

    static List<Candidate> discover(final GraphicalMatrixSpManagementConfig config,
            final GraphicalMatrixAttributeCatalog catalog,
            final GraphicalMatrixSpRegistry registry,
            final GraphicalMatrixSpAccessPolicy accessPolicy) throws Exception {
        final Set<String> declared = new LinkedHashSet<>();
        final Set<String> mapped = new LinkedHashSet<>();
        final Set<String> filtered = new LinkedHashSet<>();
        for (final Path resource : resolverResources(config.idpHome())) {
            discoverResolver(resource, declared, mapped);
        }
        discoverRegistry(config.idpHome(), mapped);
        discoverFilter(config.attributeFilterPath(), filtered);

        final Map<String, Set<String>> usage = new LinkedHashMap<>();
        for (final GraphicalMatrixSpRegistry.Entry entry : registry.entries()) {
            for (final String id : config.attributesFor(entry.attributeProfile())) {
                usage.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                    .add("profile:" + entry.name());
            }
        }
        for (final GraphicalMatrixSpAccessPolicy.Policy policy : accessPolicy.policies()) {
            for (final GraphicalMatrixSpAccessPolicy.Rule rule : policy.allow()) {
                usage.computeIfAbsent(rule.attributeId(), ignored -> new LinkedHashSet<>())
                    .add("access:" + policy.name());
            }
            for (final GraphicalMatrixSpAccessPolicy.Rule rule : policy.deny()) {
                usage.computeIfAbsent(rule.attributeId(), ignored -> new LinkedHashSet<>())
                    .add("access:" + policy.name());
            }
        }

        final Set<String> ids = new LinkedHashSet<>();
        ids.addAll(declared);
        ids.addAll(mapped);
        ids.addAll(filtered);
        ids.addAll(usage.keySet());
        catalog.attributes().forEach(item -> ids.add(item.id()));

        final List<Candidate> out = new ArrayList<>();
        for (final String id : ids.stream().sorted().toList()) {
            final GraphicalMatrixAttributeCatalog.Attribute approved = catalog.findAttribute(id);
            final String governance = approved == null
                ? config.isBlockedAttribute(id) ? "blocked" : "candidate"
                : approved.governance();
            final String resolution = approved != null
                    && "runtime-observed".equals(approved.resolution())
                ? "runtime-observed" : declared.contains(id) ? "declared" : "unknown";
            final String mapping = mapped.contains(id) ? "mapped"
                : declared.contains(id) ? "mapping-required" : "unknown";
            out.add(new Candidate(id, resolution, mapping, governance,
                usage.getOrDefault(id, Set.of())));
        }
        return List.copyOf(out);
    }

    private static void discoverResolver(final Path path, final Set<String> declared,
            final Set<String> mapped) throws Exception {
        if (!Files.isRegularFile(path)) {
            return;
        }
        final Document document = parse(path);
        final NodeList definitions = document.getElementsByTagNameNS("*", "AttributeDefinition");
        for (int i = 0; i < definitions.getLength(); i++) {
            final Element definition = (Element) definitions.item(i);
            addId(definition.getAttribute("id"), declared);
            if (definition.getElementsByTagNameNS("*", "AttributeEncoder").getLength() > 0) {
                addId(definition.getAttribute("id"), mapped);
            }
        }
        final NodeList connectors = document.getElementsByTagNameNS("*", "DataConnector");
        for (int i = 0; i < connectors.getLength(); i++) {
            final String exported = ((Element) connectors.item(i)).getAttribute("exportAttributes");
            for (final String id : exported.split("[\\s,]+")) {
                addId(id, declared);
            }
        }
    }

    private static List<Path> resolverResources(final Path idpHome) throws IOException {
        final Set<Path> resources = new LinkedHashSet<>();
        resources.add(idpHome.resolve("conf/attribute-resolver.xml").normalize());
        final Path services = idpHome.resolve("conf/services.properties");
        if (Files.isRegularFile(services)) {
            final Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(services)) {
                properties.load(input);
            }
            final String configured = properties.getProperty(
                "idp.service.attribute.resolver.resources", "");
            for (final String raw : configured.split("[,;]", -1)) {
                String value = raw.trim();
                if (value.isEmpty() || value.startsWith("classpath:")) {
                    continue;
                }
                value = value.replace("%{idp.home}", idpHome.toString());
                final Path candidate = Path.of(value);
                final Path resolved = candidate.isAbsolute() ? candidate.normalize()
                    : idpHome.resolve(candidate).normalize();
                if (!resolved.startsWith(idpHome.toAbsolutePath().normalize())) {
                    throw new IOException("attribute resolver resource is outside IDP_HOME: "
                        + resolved);
                }
                resources.add(resolved);
            }
        }
        return List.copyOf(resources);
    }

    private static void discoverRegistry(final Path idpHome, final Set<String> mapped)
            throws Exception {
        final Path defaults = idpHome.resolve("conf/attributes/default-rules.xml");
        final Path attributes = idpHome.resolve("conf/attributes").normalize();
        discoverRegistryXml(defaults, idpHome, attributes, mapped, new LinkedHashSet<>());
        final Path custom = idpHome.resolve("conf/attributes/custom");
        if (Files.isDirectory(custom)) {
            try (var files = Files.list(custom)) {
                for (final Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    if (file.getFileName().toString().endsWith(".properties")) {
                        discoverRegistryProperties(file, mapped);
                    } else if (file.getFileName().toString().endsWith(".xml")) {
                        discoverRegistryXml(file, idpHome, attributes, mapped,
                            new LinkedHashSet<>());
                    }
                }
            }
        }
    }

    private static void discoverRegistryXml(final Path path, final Path idpHome,
            final Path attributesDirectory, final Set<String> mapped, final Set<Path> visited)
            throws Exception {
        if (!Files.isRegularFile(path)) {
            return;
        }
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(attributesDirectory.toAbsolutePath().normalize())
                || !visited.add(normalized)) {
            return;
        }
        final Document document = parse(normalized);
        final NodeList propertySets = document.getElementsByTagNameNS("*", "props");
        for (int i = 0; i < propertySets.getLength(); i++) {
            final NodeList properties = ((Element) propertySets.item(i))
                .getElementsByTagNameNS("*", "prop");
            String id = "";
            String transcoders = "";
            for (int j = 0; j < properties.getLength(); j++) {
                final Element property = (Element) properties.item(j);
                if ("id".equals(property.getAttribute("key"))) {
                    id = property.getTextContent().trim();
                } else if ("transcoder".equals(property.getAttribute("key"))) {
                    transcoders = property.getTextContent();
                }
            }
            if (transcoders.contains("SAML")) {
                addId(id, mapped);
            }
        }
        final NodeList imports = document.getElementsByTagNameNS("*", "import");
        for (int i = 0; i < imports.getLength(); i++) {
            final String resource = ((Element) imports.item(i)).getAttribute("resource").trim();
            if (resource.isEmpty() || resource.startsWith("classpath:")) {
                continue;
            }
            final String expanded = resource.replace("%{idp.home}", idpHome.toString());
            final Path imported = Path.of(expanded);
            final Path resolved = (imported.isAbsolute() ? imported : normalized.getParent()
                .resolve(imported)).normalize();
            if (resolved.startsWith(attributesDirectory.toAbsolutePath().normalize())) {
                discoverRegistryXml(resolved, idpHome, attributesDirectory, mapped, visited);
            }
        }
    }

    private static void discoverRegistryProperties(final Path path, final Set<String> mapped)
            throws IOException {
        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        if (properties.getProperty("transcoder", "").contains("SAML")) {
            addId(properties.getProperty("id", ""), mapped);
        }
        for (final String key : properties.stringPropertyNames()) {
            if (!key.endsWith(".transcoder") || !properties.getProperty(key, "").contains("SAML")) {
                continue;
            }
            final String prefix = key.substring(0, key.length() - ".transcoder".length());
            addId(properties.getProperty(prefix + ".id", ""), mapped);
        }
    }

    private static void discoverFilter(final Path path, final Set<String> filtered) throws Exception {
        if (!Files.isRegularFile(path)) {
            return;
        }
        final NodeList rules = parse(path).getElementsByTagNameNS("*", "AttributeRule");
        for (int i = 0; i < rules.getLength(); i++) {
            addId(((Element) rules.item(i)).getAttribute("attributeID"), filtered);
        }
    }

    private static Document parse(final Path path) throws Exception {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("attribute configuration must not be a symbolic link: " + path);
        }
        if (Files.size(path) > 2_097_152) {
            throw new IOException("attribute configuration exceeds 2 MiB: " + path);
        }
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static void addId(final String value, final Set<String> out) {
        if (value != null && value.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            out.add(value);
        }
    }
}
