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

import java.io.InputStream;
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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class GraphicalMatrixSpAccessXmlConfig {
    static final String FUNCTION_BEAN = "shibboleth.context-check.Function";
    static final String CONDITION_BEAN = "shibboleth.context-check.Condition";
    static final String FUNCTION_CLASS =
        "io.github.yasakawa.faskw.GraphicalMatrixSpAccessFunction";
    private static final String BEANS_NS = "http://www.springframework.org/schema/beans";
    private static final String P_NS = "http://www.springframework.org/schema/p";

    record Inspection(boolean functionConfigured, boolean managedFunction,
                      boolean conditionConfigured,
                      int saml2Profiles, int contextCheckProfiles) {
        boolean conflict() {
            return functionConfigured && !managedFunction
                || conditionConfigured;
        }
    }

    private GraphicalMatrixSpAccessXmlConfig() {
    }

    static Inspection inspect(final Path contextConfig, final Path relyingParty) throws Exception {
        final Document context = parse(contextConfig);
        final Element function = bean(context, FUNCTION_BEAN);
        final Element condition = bean(context, CONDITION_BEAN);
        final boolean managed = function != null
            && managedFunction(function);

        final ProfileCounts profiles = countProfiles(parse(relyingParty));
        return new Inspection(function != null, managed, condition != null,
            profiles.total(), profiles.configured());
    }

    static void initializeContextCheck(final Path path) throws Exception {
        GraphicalMatrixSpFiles.atomicWrite(path, planContextCheck(path));
    }

    static byte[] planContextCheck(final Path path) throws Exception {
        final Document document = parse(path);
        final Element function = bean(document, FUNCTION_BEAN);
        final Element condition = bean(document, CONDITION_BEAN);
        if (function != null && !managedFunction(function)) {
            throw new IllegalStateException(
                "an existing non-2FAS-KW ContextCheck function is configured");
        }
        if (condition != null) {
            throw new IllegalStateException(
                "an existing non-2FAS-KW ContextCheck condition is configured");
        }
        if (function == null) {
            final Element root = document.getDocumentElement();
            root.appendChild(document.createTextNode("\n    "));
            root.appendChild(document.createComment(
                " BEGIN 2FAS-KW SP access function - managed by graphicalmatrix-sp.sh "));
            root.appendChild(document.createTextNode("\n    "));
            final Element value = document.createElementNS(BEANS_NS, "bean");
            value.setAttribute("id", FUNCTION_BEAN);
            value.setAttribute("class", FUNCTION_CLASS);
            final Element argument = document.createElementNS(BEANS_NS, "constructor-arg");
            argument.setAttribute("value", "%{idp.home}");
            value.appendChild(argument);
            root.appendChild(value);
            root.appendChild(document.createTextNode("\n    "));
            root.appendChild(document.createComment(
                " END 2FAS-KW SP access function "));
            root.appendChild(document.createTextNode("\n"));
        }
        return function != null ? Files.readAllBytes(path) : serialize(document);
    }

    static void initializeRelyingParty(final Path path) throws Exception {
        GraphicalMatrixSpFiles.atomicWrite(path, planRelyingParty(path));
    }

    static byte[] planRelyingParty(final Path path) throws Exception {
        final Document document = parse(path);
        boolean changed = false;
        final List<Element> refs = elements(document, "ref").stream()
            .filter(item -> "SAML2.SSO".equals(item.getAttribute("bean"))).toList();
        for (final Element ref : refs) {
            final Element profile = document.createElementNS(BEANS_NS, "bean");
            profile.setAttribute("parent", "SAML2.SSO");
            changed |= configureProfile(document, profile);
            ref.getParentNode().replaceChild(profile, ref);
            changed = true;
        }
        for (final Element profile : elements(document, "bean")) {
            if (profile.getAttribute("parent").contains("SAML2.SSO")) {
                changed |= configureProfile(document, profile);
            }
        }
        if (refs.isEmpty() && elements(document, "bean").stream()
                .noneMatch(item -> item.getAttribute("parent").contains("SAML2.SSO"))) {
            throw new IllegalStateException("no SAML2.SSO profile configuration was found");
        }
        return changed ? serialize(document) : Files.readAllBytes(path);
    }

    private static boolean configureProfile(final Document document, final Element profile) {
        for (int i = 0; i < profile.getAttributes().getLength(); i++) {
            final Node attributeNode = profile.getAttributes().item(i);
            final String normalized = attributeNode.getLocalName() == null ? "" : attributeNode.getLocalName()
                .replace("-", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
            if (P_NS.equals(attributeNode.getNamespaceURI()) && normalized.startsWith("postauthenticationflows")
                    && !"postAuthenticationFlows".equals(attributeNode.getLocalName())) {
                throw new IllegalStateException("unsupported postAuthenticationFlows reference or alias");
            }
        }
        final String attribute = profile.getAttributeNS(P_NS, "postAuthenticationFlows");
        Element property = null;
        for (Node node = profile.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item && "property".equals(item.getLocalName())
                    && "postAuthenticationFlows".equalsIgnoreCase(item.getAttribute("name").replace("-", "").replace("_", ""))) {
                if (!"postAuthenticationFlows".equals(item.getAttribute("name"))) {
                    throw new IllegalStateException("unsupported postAuthenticationFlows alias");
                }
                if (property != null || !attribute.isBlank()) { throw new IllegalStateException("ambiguous postAuthenticationFlows"); }
                property = item;
            }
        }
        if (attribute.contains("%{") || attribute.contains("#{") || attribute.contains("${")) {
            throw new IllegalStateException("unresolved postAuthenticationFlows");
        }
        final List<String> flows = new ArrayList<>();
        if (!attribute.isBlank()) {
            for (final String value : attribute.split("[,\\s]+")) {
                if (flows.contains(value)) { throw new IllegalStateException("duplicate flow"); }
                if (!value.isBlank()) { flows.add(value); }
            }
        }
        Element list = null;
        if (property != null) {
            for (Node node = property.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element item) {
                    if (list != null || !BEANS_NS.equals(item.getNamespaceURI()) || !"list".equals(item.getLocalName())) {
                        throw new IllegalStateException("unsupported postAuthenticationFlows");
                    }
                    list = item;
                }
            }
            if (list == null || property.hasAttribute("ref") || property.hasAttribute("value")) {
                throw new IllegalStateException("unsupported postAuthenticationFlows");
            }
            for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element item) {
                    final String value = item.getTextContent().trim();
                    if (!BEANS_NS.equals(item.getNamespaceURI()) || !"value".equals(item.getLocalName())
                            || item.getElementsByTagNameNS("*", "*").getLength() != 0
                            || value.isBlank() || value.contains("#{") || value.contains("%{") || value.contains("${")
                            || flows.contains(value)) { throw new IllegalStateException("unsupported or duplicate flow"); }
                    flows.add(value);
                }
            }
        }
        if (flows.contains("context-check")) { return false; }
        if (list == null) {
            property = document.createElementNS(BEANS_NS, "property");
            property.setAttribute("name", "postAuthenticationFlows");
            list = document.createElementNS(BEANS_NS, "list");
            property.appendChild(list);
            profile.insertBefore(property, profile.getFirstChild());
            profile.removeAttributeNS(P_NS, "postAuthenticationFlows");
            for (final String flow : flows) {
                final Element value = document.createElementNS(BEANS_NS, "value");
                value.setTextContent(flow);
                list.appendChild(value);
            }
        }
        final Element value = document.createElementNS(BEANS_NS, "value");
        value.setTextContent("context-check");
        list.appendChild(value);
        return true;
    }

    private static ProfileCounts countProfiles(final Document document) {
        int total = 0;
        int configured = 0;
        for (final Element ref : elements(document, "ref")) {
            if ("SAML2.SSO".equals(ref.getAttribute("bean"))) {
                total++;
            }
        }
        for (final Element bean : elements(document, "bean")) {
            if (!bean.getAttribute("parent").contains("SAML2.SSO")) {
                continue;
            }
            total++;
            int count = 0;
            final String property = bean.getAttributeNS(P_NS, "postAuthenticationFlows");
            for (final String item : property.split("[,\\s]+")) {
                if ("context-check".equals(item)) {
                    count++;
                }
            }
            for (final Element value : elements(bean, "value")) {
                if ("context-check".equals(value.getTextContent().trim())) {
                    count++;
                }
            }
            if (count == 1) {
                configured++;
            }
        }
        return new ProfileCounts(total, configured);
    }

    private record ProfileCounts(int total, int configured) {
    }

    private static boolean managedFunction(final Element function) {
        if (!FUNCTION_CLASS.equals(function.getAttribute("class")) || function.hasAttribute("parent")
                || function.hasAttribute("factory-method") || function.hasAttribute("factory-bean")) { return false; }
        int arguments = 0;
        for (Node node = function.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item) {
                if (!BEANS_NS.equals(item.getNamespaceURI()) || !"constructor-arg".equals(item.getLocalName())
                        || !"%{idp.home}".equals(item.getAttribute("value"))
                        || item.getElementsByTagNameNS("*", "*").getLength() != 0) { return false; }
                if (item.getAttributes().getLength() != 1) { return false; }
                arguments++;
            }
        }
        for (int i = 0; i < function.getAttributes().getLength(); i++) {
            final Node attr = function.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())
                    && !java.util.Set.of("id", "class").contains(attr.getNodeName())) { return false; }
        }
        return arguments == 1;
    }

    private static Element bean(final Document document, final String id) {
        final Element root = document.getDocumentElement();
        if (!BEANS_NS.equals(root.getNamespaceURI()) || !"beans".equals(root.getLocalName()) || root.hasAttribute("profile")) {
            throw new IllegalStateException("unresolved ContextCheck root");
        }
        Element found = null;
        for (final Element value : elements(document, "*")) {
            if ("import".equals(value.getLocalName()) || "alias".equals(value.getLocalName())
                    || value.hasAttribute("profile")) { throw new IllegalStateException("unresolved ContextCheck configuration"); }
            final boolean named = java.util.Arrays.asList(value.getAttribute("name").split("[,;\\s]+")).contains(id);
            if (id.equals(value.getAttribute("id")) || named) {
                if (found != null || named || !BEANS_NS.equals(value.getNamespaceURI())
                        || !"bean".equals(value.getLocalName())) { throw new IllegalStateException("ambiguous ContextCheck definition"); }
                found = value;
            }
        }
        return found;
    }

    private static List<Element> elements(final Document document, final String localName) {
        return elements(document.getDocumentElement(), localName);
    }

    private static List<Element> elements(final Element root, final String localName) {
        final List<Element> out = new ArrayList<>();
        final NodeList values = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < values.getLength(); i++) {
            out.add((Element) values.item(i));
        }
        return out;
    }

    private static Element firstElement(final Element parent, final String localName) {
        final NodeList values = parent.getChildNodes();
        for (int i = 0; i < values.getLength(); i++) {
            if (values.item(i) instanceof Element item
                    && localName.equals(item.getLocalName())) {
                return item;
            }
        }
        return null;
    }

    private static Document parse(final Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("required XML file is missing: " + path);
        }
        if (Files.isSymbolicLink(path) || Files.size(path) > 2_097_152) {
            throw new IllegalStateException("XML file is unsafe or exceeds 2 MiB: " + path);
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

    private static byte[] serialize(final Document document) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }
}
