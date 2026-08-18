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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class GraphicalMatrixLdapResolverConfig {
    private static final String RESOLVER_NS = "urn:mace:shibboleth:2.0:resolver";
    private static final int MAX_FILE_BYTES = 2_097_152;

    record Plan(String attributeId, String sourceAttribute, String dataConnector,
                boolean returnAttributesUpdated, boolean alreadyConfigured) {
    }

    record InitializationPlan(String dataConnector, String searchAttribute,
                              boolean alreadyConfigured) {
    }

    private GraphicalMatrixLdapResolverConfig() {
    }

    static InitializationPlan inspectInitialization(final Path path,
            final String requestedConnector, final String searchAttribute) throws Exception {
        validateId(searchAttribute, "LDAP search attribute");
        if (!requestedConnector.isBlank()) {
            validateId(requestedConnector, "data connector ID");
        }
        final Document document = parse(path);
        final List<Element> connectors = ldapConnectors(document);
        if (!requestedConnector.isBlank()) {
            final Element existing = dataConnector(document, requestedConnector);
            if (existing != null) {
                if (!isLdapConnector(existing)) {
                    throw new IllegalStateException("data connector ID is already used by a "
                        + "non-LDAP connector: " + requestedConnector);
                }
                return new InitializationPlan(requestedConnector, searchAttribute, true);
            }
            if (!connectors.isEmpty()) {
                throw new IllegalStateException("an LDAPDirectory DataConnector already exists; "
                    + "use it instead of creating another connector; available="
                    + connectorIds(connectors));
            }
            return new InitializationPlan(requestedConnector, searchAttribute, false);
        }
        if (connectors.size() == 1) {
            return new InitializationPlan(connectors.get(0).getAttribute("id"),
                searchAttribute, true);
        }
        if (connectors.size() > 1) {
            throw new IllegalStateException("multiple LDAPDirectory DataConnectors were found; "
                + "specify --data-connector; available=" + connectorIds(connectors));
        }
        return new InitializationPlan("graphicalmatrixLdap", searchAttribute, false);
    }

    static InitializationPlan initialize(final Path path, final String requestedConnector,
            final String searchAttribute) throws Exception {
        final InitializationPlan plan = inspectInitialization(path,
            requestedConnector, searchAttribute);
        if (plan.alreadyConfigured()) {
            return plan;
        }
        final Document document = parse(path);
        final Element root = document.getDocumentElement();
        final String indent = "\n    ";

        root.appendChild(document.createTextNode(indent));
        root.appendChild(document.createComment(
            " BEGIN 2FAS-KW managed LDAP data connector " + plan.dataConnector() + " "));
        root.appendChild(document.createTextNode(indent));
        final Element connector = document.createElementNS(RESOLVER_NS,
            qualifiedName(root, "DataConnector"));
        connector.setAttribute("id", plan.dataConnector());
        connector.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            "xsi:type", "LDAPDirectory");
        connector.setAttribute("ldapURL", "%{idp.attribute.resolver.LDAP.ldapURL}");
        connector.setAttribute("baseDN", "%{idp.attribute.resolver.LDAP.baseDN}");
        connector.setAttribute("principal", "%{idp.attribute.resolver.LDAP.bindDN}");
        connector.setAttribute("principalCredential",
            "%{idp.attribute.resolver.LDAP.bindDNCredential}");
        connector.setAttribute("useStartTLS",
            "%{idp.attribute.resolver.LDAP.useStartTLS:true}");
        connector.setAttribute("noResultIsError", "false");
        connector.setAttribute("multipleResultsIsError", "true");
        connector.appendChild(document.createTextNode(indent + "    "));
        final Element filter = document.createElementNS(RESOLVER_NS,
            qualifiedName(root, "FilterTemplate"));
        filter.appendChild(document.createCDATASection("\n            ("
            + plan.searchAttribute() + "=$resolutionContext.principal)\n        "));
        connector.appendChild(filter);
        connector.appendChild(document.createTextNode(indent));
        root.appendChild(connector);
        root.appendChild(document.createTextNode(indent));
        root.appendChild(document.createComment(
            " END 2FAS-KW managed LDAP data connector " + plan.dataConnector() + " "));
        root.appendChild(document.createTextNode("\n"));

        final byte[] output = serialize(document);
        validateBytes(output);
        GraphicalMatrixSpFiles.atomicWrite(path, output);
        return plan;
    }

    static Plan inspect(final Path path, final String attributeId,
            final String sourceAttribute, final String requestedConnector) throws Exception {
        validateId(attributeId, "attribute ID");
        validateId(sourceAttribute, "LDAP source attribute");
        if (!requestedConnector.isBlank()) {
            validateId(requestedConnector, "data connector ID");
        }
        final Document document = parse(path);
        final List<Element> connectors = ldapConnectors(document);
        final Element connector = selectConnector(connectors, requestedConnector);
        final Element existing = definition(document, attributeId);
        if (existing != null) {
            final Element input = firstChild(existing, "InputDataConnector");
            if (input != null
                    && connector.getAttribute("id").equals(input.getAttribute("ref"))
                    && sourceAttribute.equals(input.getAttribute("attributeNames"))) {
                return new Plan(attributeId, sourceAttribute, connector.getAttribute("id"),
                    false, true);
            }
            throw new IllegalStateException("attribute is already defined outside the requested "
                + "LDAP resolver mapping: " + attributeId
                + "; review the existing AttributeDefinition manually");
        }
        return new Plan(attributeId, sourceAttribute, connector.getAttribute("id"),
            requiresReturnAttributeUpdate(connector, sourceAttribute), false);
    }

    static Plan add(final Path path, final String attributeId,
            final String sourceAttribute, final String requestedConnector) throws Exception {
        final Plan plan = inspect(path, attributeId, sourceAttribute, requestedConnector);
        if (plan.alreadyConfigured()) {
            return plan;
        }
        final Document document = parse(path);
        final Element connector = selectConnector(ldapConnectors(document), plan.dataConnector());
        final Element root = document.getDocumentElement();

        final String indent = "\n    ";
        final NodeList allConnectors = document.getElementsByTagNameNS("*", "DataConnector");
        final Node insertionPoint = allConnectors.getLength() == 0
            ? connector : allConnectors.item(0);
        root.insertBefore(document.createTextNode(indent), insertionPoint);
        root.insertBefore(document.createComment(
            " BEGIN 2FAS-KW managed LDAP attribute " + attributeId + " "), insertionPoint);
        root.insertBefore(document.createTextNode(indent), insertionPoint);

        final Element definition = document.createElementNS(RESOLVER_NS,
            qualifiedName(root, "AttributeDefinition"));
        definition.setAttribute("id", attributeId);
        definition.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            "xsi:type", "Simple");
        definition.appendChild(document.createTextNode(indent + "    "));
        final Element input = document.createElementNS(RESOLVER_NS,
            qualifiedName(root, "InputDataConnector"));
        input.setAttribute("ref", plan.dataConnector());
        input.setAttribute("attributeNames", sourceAttribute);
        definition.appendChild(input);
        definition.appendChild(document.createTextNode(indent));
        root.insertBefore(definition, insertionPoint);
        root.insertBefore(document.createTextNode(indent), insertionPoint);
        root.insertBefore(document.createComment(
            " END 2FAS-KW managed LDAP attribute " + attributeId + " "), insertionPoint);
        root.insertBefore(document.createTextNode(indent), insertionPoint);

        if (plan.returnAttributesUpdated()) {
            final Element returnAttributes = firstChild(connector, "ReturnAttributes");
            final Set<String> values = words(returnAttributes.getTextContent());
            values.add(sourceAttribute);
            returnAttributes.setTextContent(String.join(" ", values));
        }

        final byte[] output = serialize(document);
        validateBytes(output);
        GraphicalMatrixSpFiles.atomicWrite(path, output);
        return plan;
    }

    private static Element selectConnector(final List<Element> connectors,
            final String requested) {
        if (!requested.isBlank()) {
            return connectors.stream()
                .filter(item -> requested.equals(item.getAttribute("id")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                    "LDAP data connector not found: " + requested + "; available="
                        + connectorIds(connectors)));
        }
        if (connectors.isEmpty()) {
            throw new IllegalStateException(
                "no LDAPDirectory DataConnector was found in attribute-resolver.xml");
        }
        if (connectors.size() > 1) {
            throw new IllegalStateException("multiple LDAPDirectory DataConnectors were found; "
                + "specify --data-connector; available=" + connectorIds(connectors));
        }
        return connectors.get(0);
    }

    private static List<Element> ldapConnectors(final Document document) {
        final List<Element> out = new ArrayList<>();
        final NodeList values = document.getElementsByTagNameNS("*", "DataConnector");
        for (int i = 0; i < values.getLength(); i++) {
            final Element element = (Element) values.item(i);
            if (isLdapConnector(element) && !element.getAttribute("id").isBlank()) {
                out.add(element);
            }
        }
        return List.copyOf(out);
    }

    private static Element dataConnector(final Document document, final String id) {
        final NodeList connectors = document.getElementsByTagNameNS("*", "DataConnector");
        for (int i = 0; i < connectors.getLength(); i++) {
            final Element connector = (Element) connectors.item(i);
            if (id.equals(connector.getAttribute("id"))) {
                return connector;
            }
        }
        return null;
    }

    private static boolean isLdapConnector(final Element element) {
        String type = element.getAttributeNS(
            XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        if (type.isBlank()) {
            type = element.getAttribute("xsi:type");
        }
        final int separator = type.indexOf(':');
        final String localType = separator >= 0 ? type.substring(separator + 1) : type;
        return "LDAPDirectory".equals(localType);
    }

    private static Element definition(final Document document, final String id) {
        final NodeList definitions = document.getElementsByTagNameNS("*", "AttributeDefinition");
        for (int i = 0; i < definitions.getLength(); i++) {
            final Element candidate = (Element) definitions.item(i);
            if (id.equals(candidate.getAttribute("id"))) {
                return candidate;
            }
        }
        return null;
    }

    private static Element firstChild(final Element parent, final String localName) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static boolean requiresReturnAttributeUpdate(final Element connector,
            final String sourceAttribute) {
        final Element returnAttributes = firstChild(connector, "ReturnAttributes");
        return returnAttributes != null
            && !words(returnAttributes.getTextContent()).contains(sourceAttribute);
    }

    private static Set<String> words(final String value) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String item : value.trim().split("[\\s,]+", -1)) {
            if (!item.isBlank()) {
                validateId(item, "ReturnAttributes value");
                out.add(item);
            }
        }
        return out;
    }

    private static String connectorIds(final List<Element> connectors) {
        return connectors.isEmpty() ? "-" : String.join(",", connectors.stream()
            .map(item -> item.getAttribute("id")).toList());
    }

    private static String qualifiedName(final Element root, final String localName) {
        return root.getPrefix() == null || root.getPrefix().isBlank()
            ? localName : root.getPrefix() + ":" + localName;
    }

    private static void validateId(final String value, final String label) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
    }

    private static Document parse(final Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("required Attribute Resolver file is missing: " + path);
        }
        if (Files.isSymbolicLink(path) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IllegalStateException(
                "Attribute Resolver file is unsafe or exceeds 2 MiB: " + path);
        }
        final DocumentBuilderFactory factory = secureFactory();
        try (InputStream input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static void validateBytes(final byte[] content) throws Exception {
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalStateException("generated Attribute Resolver exceeds 2 MiB");
        }
        secureFactory().newDocumentBuilder().parse(
            new java.io.ByteArrayInputStream(content));
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static byte[] serialize(final Document document) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }
}
