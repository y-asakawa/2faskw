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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class GraphicalMatrixSpXmlConfig {
    static final String PROVIDER_ID = "GraphicalMatrixManagedSPMetadata";
    static final String LEGACY_PROVIDER_ID = "2FAS-KWManagedSPMetadata";
    private static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final String ATTRIBUTE_BEGIN = "BEGIN 2FAS-KW managed SPs - do not edit manually";
    private static final String ATTRIBUTE_END = "END 2FAS-KW managed SPs";

    record Inventory(String sourceId, String sourceType, String entityId, Path metadataFile,
                     String status, String detail) {
    }

    private GraphicalMatrixSpXmlConfig() {
    }

    static boolean hasManagedProvider(final Path path) throws Exception {
        final Document document = parse(path);
        return providerById(document, PROVIDER_ID) != null;
    }

    static boolean hasManagedAttributeBlock(final Path path) throws Exception {
        return managedBlockBounds(parse(path)) != null;
    }

    static void initializeMetadataProvider(final Path path, final Path idpHome) throws Exception {
        final Document document = parse(path);
        final Element provider = providerById(document, PROVIDER_ID);
        final Element legacyProvider = providerById(document, LEGACY_PROVIDER_ID);
        if (provider != null && legacyProvider != null) {
            throw new IllegalArgumentException("both current and invalid legacy managed metadata "
                + "provider IDs are configured");
        }
        if (provider != null) {
            return;
        }
        if (legacyProvider != null) {
            legacyProvider.setAttribute("id", PROVIDER_ID);
            write(path, document);
            return;
        }
        final Element parent = uniqueChain(document);
        final String namespace = parent.getNamespaceURI();
        final Element newProvider = document.createElementNS(namespace, "MetadataProvider");
        newProvider.setAttribute("id", PROVIDER_ID);
        newProvider.setAttributeNS(XSI_NS, "xsi:type", "LocalDynamicMetadataProvider");
        newProvider.setAttribute("sourceDirectory", "%{idp.home}/metadata/2faskw-managed-sp");
        newProvider.setAttribute("refreshDelayFactor", "0.02");
        newProvider.setAttribute("maxCacheDuration", "PT8H");
        newProvider.setAttribute("maxIdleEntityData", "PT30M");
        parent.appendChild(document.createTextNode("\n    "));
        parent.appendChild(newProvider);
        parent.appendChild(document.createTextNode("\n"));
        write(path, document);
    }

    static void initializeAttributeBlock(final Path path) throws Exception {
        final Document document = parse(path);
        if (managedBlockBounds(document) != null) {
            return;
        }
        final Element root = document.getDocumentElement();
        root.appendChild(document.createTextNode("\n    "));
        root.appendChild(document.createComment(" " + ATTRIBUTE_BEGIN + " "));
        root.appendChild(document.createTextNode("\n    "));
        root.appendChild(document.createComment(" " + ATTRIBUTE_END + " "));
        root.appendChild(document.createTextNode("\n"));
        write(path, document);
    }

    static void renderManagedAttributes(final Path path,
            final List<GraphicalMatrixSpRegistry.Entry> entries,
            final GraphicalMatrixSpManagementConfig config) throws Exception {
        final Document document = parse(path);
        final Element root = document.getDocumentElement();
        removeManagedBlock(document);
        root.appendChild(document.createTextNode("\n    "));
        root.appendChild(document.createComment(" " + ATTRIBUTE_BEGIN + " "));
        for (final GraphicalMatrixSpRegistry.Entry entry : entries) {
            if (!"ACTIVE".equals(entry.status())) {
                continue;
            }
            final List<String> attributes = config.attributesFor(entry.attributeProfile());
            if (attributes.isEmpty()) {
                continue;
            }
            root.appendChild(document.createTextNode("\n    "));
            root.appendChild(attributePolicy(document, root.getNamespaceURI(), entry, attributes));
        }
        root.appendChild(document.createTextNode("\n    "));
        root.appendChild(document.createComment(" " + ATTRIBUTE_END + " "));
        root.appendChild(document.createTextNode("\n"));
        write(path, document);
    }

    static String extractLegacyAttributePolicies(final Path path, final String entityId,
            final boolean remove) throws Exception {
        final Document document = parse(path);
        final List<Element> matches = matchingPolicies(document, entityId);
        final StringBuilder out = new StringBuilder();
        for (final Element policy : matches) {
            out.append(nodeXml(policy)).append('\n');
            if (remove) {
                policy.getParentNode().removeChild(policy);
            }
        }
        if (remove && !matches.isEmpty()) {
            write(path, document);
        }
        return out.toString();
    }

    static String inferAttributeProfile(final Path path, final String entityId) throws Exception {
        final List<Element> matches = matchingPolicies(parse(path), entityId);
        if (matches.isEmpty()) {
            return "none";
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException("multiple attribute policies match entityID");
        }
        final List<String> attributes = new ArrayList<>();
        final NodeList rules = matches.get(0).getElementsByTagNameNS("*", "AttributeRule");
        for (int i = 0; i < rules.getLength(); i++) {
            final String id = ((Element) rules.item(i)).getAttribute("attributeID");
            if (!id.isBlank()) {
                attributes.add(id);
            }
        }
        if (attributes.equals(List.of("uid"))) {
            return "uid";
        }
        if (attributes.size() == 2 && attributes.contains("uid") && attributes.contains("mail")) {
            return "uid-mail";
        }
        throw new IllegalArgumentException(
            "legacy attribute policy is not equivalent to none, uid, or uid-mail");
    }

    static void restoreLegacyAttributePolicies(final Path path, final String fragment) throws Exception {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        final Document document = parse(path);
        final Element root = document.getDocumentElement();
        final String wrapper = "<Wrapper xmlns=\"" + xml(root.getNamespaceURI())
            + "\" xmlns:xsi=\"" + XSI_NS + "\">" + fragment + "</Wrapper>";
        final Document parsed = parse(wrapper.getBytes(StandardCharsets.UTF_8));
        final NodeList nodes = parsed.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            if (node instanceof Element) {
                root.appendChild(document.createTextNode("\n    "));
                root.appendChild(document.importNode(node, true));
            }
        }
        root.appendChild(document.createTextNode("\n"));
        write(path, document);
    }

    static List<Inventory> inventory(final GraphicalMatrixSpManagementConfig config) throws Exception {
        final Document document = parse(config.metadataProvidersPath());
        final List<Inventory> out = new ArrayList<>();
        scanProviders(config, document.getDocumentElement(), out);
        return List.copyOf(out);
    }

    static String providerXml(final Path path, final String providerId) throws Exception {
        final Element provider = providerById(parse(path), providerId);
        if (provider == null) {
            throw new IllegalArgumentException("metadata provider not found: " + providerId);
        }
        return nodeXml(provider);
    }

    static void removeProvider(final Path path, final String providerId) throws Exception {
        final Document document = parse(path);
        final Element provider = providerById(document, providerId);
        if (provider == null) {
            throw new IllegalArgumentException("metadata provider not found: " + providerId);
        }
        if (PROVIDER_ID.equals(providerId)) {
            throw new IllegalArgumentException("managed metadata provider cannot be removed by adopt");
        }
        provider.getParentNode().removeChild(provider);
        write(path, document);
    }

    static void restoreProvider(final Path path, final String providerXml) throws Exception {
        final Document document = parse(path);
        final Element parent = uniqueChain(document);
        final String wrapper = "<Wrapper xmlns=\"" + xml(parent.getNamespaceURI())
            + "\" xmlns:xsi=\"" + XSI_NS + "\">" + providerXml + "</Wrapper>";
        final Document parsed = parse(wrapper.getBytes(StandardCharsets.UTF_8));
        Element element = null;
        final NodeList nodes = parsed.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element found) {
                element = found;
                break;
            }
        }
        if (element == null) {
            throw new IllegalArgumentException("saved provider XML has no element");
        }
        final String id = element.getAttribute("id");
        if (id.isBlank() || providerById(document, id) != null) {
            throw new IllegalArgumentException("cannot restore duplicate or unnamed metadata provider: " + id);
        }
        parent.appendChild(document.createTextNode("\n    "));
        parent.appendChild(document.importNode(element, true));
        parent.appendChild(document.createTextNode("\n"));
        write(path, document);
    }

    private static void scanProviders(final GraphicalMatrixSpManagementConfig config,
            final Element parent, final List<Inventory> out) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element provider)
                    || !"MetadataProvider".equals(provider.getLocalName())) {
                continue;
            }
            final String id = provider.getAttribute("id");
            final String type = providerType(provider);
            try {
                switch (type) {
                    case "ChainingMetadataProvider" -> scanProviders(config, provider, out);
                    case "FilesystemMetadataProvider" -> scanFilesystem(config, provider, id, out);
                    case "LocalDynamicMetadataProvider" -> scanLocalDynamic(config, provider, id, out);
                    case "InlineMetadataProvider" -> scanInline(provider, id, out);
                    case "FileBackedHTTPMetadataProvider", "DynamicHTTPMetadataProvider" -> out.add(
                        new Inventory(id, type, "", null, "REMOTE_SOURCE",
                            remoteDetail(provider)));
                    default -> out.add(new Inventory(id, type, "", null, "UNRESOLVED",
                        "unsupported metadata provider type"));
                }
            } catch (Exception ex) {
                out.add(new Inventory(id, type, "", null, "INVALID_METADATA", ex.getMessage()));
            }
        }
    }

    private static void scanFilesystem(final GraphicalMatrixSpManagementConfig config,
            final Element provider, final String id, final List<Inventory> out) throws Exception {
        final Path file = resolve(config.idpHome(), provider.getAttribute("metadataFile"));
        final GraphicalMatrixSpMetadata.Parsed metadata = GraphicalMatrixSpMetadata.inspectExisting(config, file);
        out.add(new Inventory(id, "FilesystemMetadataProvider", metadata.entityId(), file,
            PROVIDER_ID.equals(id) ? "MANAGED" : "EXISTING_LOCAL", metadata.sha256()));
    }

    private static void scanLocalDynamic(final GraphicalMatrixSpManagementConfig config,
            final Element provider, final String id, final List<Inventory> out) throws Exception {
        final Path directory = resolve(config.idpHome(), provider.getAttribute("sourceDirectory"));
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (final Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted().toList()) {
                try {
                    final GraphicalMatrixSpMetadata.Parsed metadata =
                        GraphicalMatrixSpMetadata.inspectExisting(config, file);
                    final String expected = GraphicalMatrixSpMetadata.standardFileName(metadata.entityId());
                    final String detail = file.getFileName().toString().equals(expected)
                        ? metadata.sha256() : "non-standard file name; expected=" + expected;
                    out.add(new Inventory(id, "LocalDynamicMetadataProvider", metadata.entityId(), file,
                        PROVIDER_ID.equals(id) ? "MANAGED" : "EXISTING_LOCAL", detail));
                } catch (Exception ex) {
                    out.add(new Inventory(id, "LocalDynamicMetadataProvider", "", file,
                        "INVALID_METADATA", ex.getMessage()));
                }
            }
        }
    }

    private static void scanInline(final Element provider, final String id,
            final List<Inventory> out) {
        final NodeList entities = provider.getElementsByTagNameNS(
            "urn:oasis:names:tc:SAML:2.0:metadata", "EntityDescriptor");
        if (entities.getLength() == 0) {
            out.add(new Inventory(id, "InlineMetadataProvider", "", null,
                "INVALID_METADATA", "inline provider has no EntityDescriptor"));
        }
        for (int i = 0; i < entities.getLength(); i++) {
            out.add(new Inventory(id, "InlineMetadataProvider",
                ((Element) entities.item(i)).getAttribute("entityID"), null,
                "EXISTING_LOCAL", "inline metadata"));
        }
    }

    private static String remoteDetail(final Element provider) {
        for (final String name : List.of("metadataURL", "httpClientRef", "backingFile")) {
            if (provider.hasAttribute(name)) {
                return name + "=" + provider.getAttribute(name);
            }
        }
        return "remote metadata source; not fetched";
    }

    private static Element attributePolicy(final Document document, final String namespace,
            final GraphicalMatrixSpRegistry.Entry entry, final List<String> attributes) {
        final Element policy = document.createElementNS(namespace, "AttributeFilterPolicy");
        policy.setAttribute("id", "2faskw-" + entry.name());
        final Element requirement = document.createElementNS(namespace, "PolicyRequirementRule");
        requirement.setAttributeNS(XSI_NS, "xsi:type", "Requester");
        requirement.setAttribute("value", entry.entityId());
        policy.appendChild(requirement);
        for (final String attribute : attributes) {
            final Element rule = document.createElementNS(namespace, "AttributeRule");
            rule.setAttribute("attributeID", attribute);
            final Element permit = document.createElementNS(namespace, "PermitValueRule");
            permit.setAttributeNS(XSI_NS, "xsi:type", "ANY");
            rule.appendChild(permit);
            policy.appendChild(rule);
        }
        return policy;
    }

    private static List<Element> matchingPolicies(final Document document, final String entityId) {
        final List<Element> out = new ArrayList<>();
        final NodeList policies = document.getElementsByTagNameNS("*", "AttributeFilterPolicy");
        for (int i = 0; i < policies.getLength(); i++) {
            final Element policy = (Element) policies.item(i);
            if (policy.getAttribute("id").startsWith("2faskw-")) {
                continue;
            }
            final NodeList requirements = policy.getElementsByTagNameNS("*", "PolicyRequirementRule");
            for (int j = 0; j < requirements.getLength(); j++) {
                if (entityId.equals(((Element) requirements.item(j)).getAttribute("value"))) {
                    out.add(policy);
                    break;
                }
            }
        }
        return out;
    }

    private static void removeManagedBlock(final Document document) {
        final Node[] bounds = managedBlockBounds(document);
        if (bounds == null) {
            return;
        }
        final Node parent = bounds[0].getParentNode();
        Node current = bounds[0];
        while (current != null) {
            final Node next = current.getNextSibling();
            parent.removeChild(current);
            if (current == bounds[1]) {
                break;
            }
            current = next;
        }
    }

    private static Node[] managedBlockBounds(final Document document) {
        Node begin = null;
        Node end = null;
        final NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node node = children.item(i);
            if (node instanceof Comment comment) {
                final String value = comment.getData().trim();
                if (ATTRIBUTE_BEGIN.equals(value)) {
                    if (begin != null) {
                        throw new IllegalArgumentException("duplicate managed attribute BEGIN marker");
                    }
                    begin = node;
                } else if (ATTRIBUTE_END.equals(value)) {
                    if (end != null) {
                        throw new IllegalArgumentException("duplicate managed attribute END marker");
                    }
                    end = node;
                }
            }
        }
        if (begin == null && end == null) {
            return null;
        }
        if (begin == null || end == null) {
            throw new IllegalArgumentException("incomplete managed attribute marker block");
        }
        return new Node[] {begin, end};
    }

    private static Element uniqueChain(final Document document) {
        final Element root = document.getDocumentElement();
        if ("MetadataProvider".equals(root.getLocalName())
                && "ChainingMetadataProvider".equals(providerType(root))) {
            return root;
        }
        final List<Element> chains = new ArrayList<>();
        final NodeList providers = document.getElementsByTagNameNS("*", "MetadataProvider");
        for (int i = 0; i < providers.getLength(); i++) {
            final Element provider = (Element) providers.item(i);
            if ("ChainingMetadataProvider".equals(providerType(provider))) {
                chains.add(provider);
            }
        }
        if (chains.size() != 1) {
            throw new IllegalArgumentException("a unique ChainingMetadataProvider was not found");
        }
        return chains.get(0);
    }

    private static Element providerById(final Document document, final String id) {
        final NodeList providers = document.getElementsByTagNameNS("*", "MetadataProvider");
        for (int i = 0; i < providers.getLength(); i++) {
            final Element provider = (Element) providers.item(i);
            if (id.equals(provider.getAttribute("id"))) {
                return provider;
            }
        }
        if (document.getDocumentElement() != null
                && "MetadataProvider".equals(document.getDocumentElement().getLocalName())
                && id.equals(document.getDocumentElement().getAttribute("id"))) {
            return document.getDocumentElement();
        }
        return null;
    }

    private static String providerType(final Element provider) {
        String type = provider.getAttributeNS(XSI_NS, "type");
        if (type.isBlank()) {
            type = provider.getAttribute("xsi:type");
        }
        final int separator = type.indexOf(':');
        return separator >= 0 ? type.substring(separator + 1) : type;
    }

    private static Path resolve(final Path idpHome, final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("metadata path is missing");
        }
        final String replaced = raw.replace("%{idp.home}", idpHome.toString())
            .replace("${idp.home}", idpHome.toString());
        final Path path = Path.of(replaced);
        return (path.isAbsolute() ? path : idpHome.resolve(path)).normalize();
    }

    private static Document parse(final Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IOException("required XML file is missing: " + path);
        }
        return parse(Files.readAllBytes(path));
    }

    private static Document parse(final byte[] bytes) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static void write(final Path path, final Document document) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        GraphicalMatrixSpFiles.atomicWrite(path, output.toByteArray());
    }

    private static String nodeXml(final Node node) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(node), new StreamResult(output));
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String xml(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }
}
