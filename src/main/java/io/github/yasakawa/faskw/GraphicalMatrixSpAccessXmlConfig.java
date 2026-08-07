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
                      boolean conditionConfigured, boolean stockCondition,
                      int saml2Profiles, int contextCheckProfiles) {
        boolean conflict() {
            return functionConfigured && !managedFunction
                || conditionConfigured && !stockCondition;
        }
    }

    private GraphicalMatrixSpAccessXmlConfig() {
    }

    static Inspection inspect(final Path contextConfig, final Path relyingParty) throws Exception {
        final Document context = parse(contextConfig);
        final Element function = bean(context, FUNCTION_BEAN);
        final Element condition = bean(context, CONDITION_BEAN);
        final boolean managed = function != null
            && FUNCTION_CLASS.equals(function.getAttribute("class"));
        final boolean stock = condition != null && stockCondition(condition);
        final ProfileCounts profiles = countProfiles(parse(relyingParty));
        return new Inspection(function != null, managed, condition != null, stock,
            profiles.total(), profiles.configured());
    }

    static void initializeContextCheck(final Path path) throws Exception {
        final Document document = parse(path);
        final Element function = bean(document, FUNCTION_BEAN);
        final Element condition = bean(document, CONDITION_BEAN);
        if (function != null && !FUNCTION_CLASS.equals(function.getAttribute("class"))) {
            throw new IllegalStateException(
                "an existing non-2FAS-KW ContextCheck function is configured");
        }
        if (condition != null && !stockCondition(condition)) {
            throw new IllegalStateException(
                "an existing non-2FAS-KW ContextCheck condition is configured");
        }
        if (condition != null) {
            condition.getParentNode().removeChild(condition);
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
        write(path, document);
    }

    static void initializeRelyingParty(final Path path) throws Exception {
        final Document document = parse(path);
        final List<Element> refs = elements(document, "ref").stream()
            .filter(item -> "SAML2.SSO".equals(item.getAttribute("bean"))).toList();
        for (final Element ref : refs) {
            final Element profile = document.createElementNS(BEANS_NS, "bean");
            profile.setAttribute("parent", "SAML2.SSO");
            configureProfile(document, profile);
            ref.getParentNode().replaceChild(profile, ref);
        }
        for (final Element profile : elements(document, "bean")) {
            if (profile.getAttribute("parent").contains("SAML2.SSO")) {
                configureProfile(document, profile);
            }
        }
        if (refs.isEmpty() && elements(document, "bean").stream()
                .noneMatch(item -> item.getAttribute("parent").contains("SAML2.SSO"))) {
            throw new IllegalStateException("no SAML2.SSO profile configuration was found");
        }
        write(path, document);
    }

    private static void configureProfile(final Document document, final Element profile) {
        final String propertyAttribute = profile.getAttributeNS(P_NS, "postAuthenticationFlows");
        final List<String> flows = new ArrayList<>();
        if (!propertyAttribute.isBlank()) {
            for (final String value : propertyAttribute.split("[,\\s]+")) {
                if (!value.isBlank() && !flows.contains(value)) {
                    flows.add(value);
                }
            }
            profile.removeAttributeNS(P_NS, "postAuthenticationFlows");
        }
        Element property = null;
        final NodeList children = profile.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element item
                    && "property".equals(item.getLocalName())
                    && "postAuthenticationFlows".equals(item.getAttribute("name"))) {
                if (property != null) {
                    throw new IllegalStateException(
                        "duplicate postAuthenticationFlows property in SAML2.SSO profile");
                }
                property = item;
            }
        }
        if (property == null) {
            property = document.createElementNS(BEANS_NS, "property");
            property.setAttribute("name", "postAuthenticationFlows");
            final Element list = document.createElementNS(BEANS_NS, "list");
            property.appendChild(list);
            profile.insertBefore(property, profile.getFirstChild());
        }
        Element list = firstElement(property, "list");
        if (list == null) {
            throw new IllegalStateException(
                "postAuthenticationFlows must be represented as a Spring list");
        }
        final List<Node> duplicateValues = new ArrayList<>();
        for (final Element value : elements(list, "value")) {
            final String text = value.getTextContent().trim();
            if ("context-check".equals(text)) {
                duplicateValues.add(value);
            } else if (!text.isBlank() && !flows.contains(text)) {
                flows.add(text);
            }
        }
        for (final Node node : duplicateValues) {
            node.getParentNode().removeChild(node);
        }
        while (list.hasChildNodes()) {
            list.removeChild(list.getFirstChild());
        }
        final Element contextCheck = document.createElementNS(BEANS_NS, "value");
        contextCheck.setTextContent("context-check");
        list.appendChild(contextCheck);
        for (final String flow : flows) {
            if ("context-check".equals(flow)) {
                continue;
            }
            final Element value = document.createElementNS(BEANS_NS, "value");
            value.setTextContent(flow);
            list.appendChild(value);
        }
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

    private static boolean stockCondition(final Element condition) {
        boolean relyingPartyCondition = false;
        boolean simpleAttributeCondition = false;
        for (final Element candidate : elements(condition, "bean")) {
            final String parent = candidate.getAttribute("parent");
            relyingPartyCondition |= "shibboleth.Conditions.RelyingPartyId".equals(parent);
            simpleAttributeCondition |= "shibboleth.Conditions.SimpleAttribute".equals(parent);
        }
        boolean exampleAttribute = false;
        for (final Element entry : elements(condition, "entry")) {
            if ("eppn".equals(entry.getAttribute("key"))) {
                exampleAttribute = true;
                break;
            }
        }
        return relyingPartyCondition && simpleAttributeCondition && exampleAttribute;
    }

    private static Element bean(final Document document, final String id) {
        for (final Element value : elements(document, "bean")) {
            if (id.equals(value.getAttribute("id"))) {
                return value;
            }
        }
        return null;
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

    private static void write(final Path path, final Document document) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        GraphicalMatrixSpFiles.atomicWrite(path, output.toByteArray());
    }
}
