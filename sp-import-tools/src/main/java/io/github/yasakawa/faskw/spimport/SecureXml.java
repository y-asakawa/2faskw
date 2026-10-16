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

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

final class SecureXml {
    static final String MD = "urn:oasis:names:tc:SAML:2.0:metadata";
    static final String AF = "urn:mace:shibboleth:2.0:afp";
    static final String SPRING = "http://www.springframework.org/schema/beans";
    static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    static final class Node {
        final String namespace;
        final String name;
        final String location;
        String source;
        Node parent;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final Map<String, String> namespaces = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        Node(String namespace, String name, String location) { this.namespace = namespace; this.name = name; this.location = location; }
        String attr(String key) { return attributes.getOrDefault(key, ""); }
        String type() { String value = attr("{" + XSI + "}type"); return value.substring(value.lastIndexOf(':') + 1); }
        String typeNamespace() {
            String value = attr("{" + XSI + "}type");
            return namespaces.getOrDefault(value.contains(":") ? value.substring(0, value.indexOf(':')) : "", "");
        }
        List<Node> descendants(String wanted) { return descendants().stream().filter(n -> n.name.equals(wanted)).toList(); }
        List<Node> descendants() {
            List<Node> result = new ArrayList<>();
            result.add(this);
            for (Node child : children) result.addAll(child.descendants());
            return result;
        }
    }

    static Node parse(byte[] bytes, String source) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var parser = factory.newSAXParser();
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var reader = parser.getXMLReader();
        Handler handler = new Handler(source);
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver(handler);
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        return handler.root;
    }

    private static final class Handler extends DefaultHandler2 {
        private final String source;
        private final ArrayDeque<Node> stack = new ArrayDeque<>();
        private Locator locator;
        private Node root;
        private int nodes;
        private final Map<String, String> pendingPrefixes = new LinkedHashMap<>();
        Handler(String source) { this.source = source; }
        @Override public void setDocumentLocator(Locator value) { locator = value; }
        @Override public void startPrefixMapping(String prefix, String uri) { pendingPrefixes.put(prefix, uri); }
        @Override public void startElement(String uri, String local, String qName, Attributes attributes) throws SAXException {
            if (stack.size() >= 64 || ++nodes > 200000) throw new SAXException("RESOURCE_LIMIT_EXCEEDED: XML depth/node count");
            Node node = new Node(uri, local, source + ":" + locator.getLineNumber() + ":" + locator.getColumnNumber());
            node.source = source;
            if (!stack.isEmpty()) node.namespaces.putAll(stack.peek().namespaces);
            node.namespaces.putAll(pendingPrefixes);
            pendingPrefixes.clear();
            for (int i = 0; i < attributes.getLength(); i++) {
                String key = attributes.getURI(i).isEmpty() ? attributes.getLocalName(i)
                    : "{" + attributes.getURI(i) + "}" + attributes.getLocalName(i);
                node.attributes.put(key, attributes.getValue(i));
            }
            if (stack.isEmpty()) root = node;
            else { node.parent = stack.peek(); stack.peek().children.add(node); }
            stack.push(node);
        }
        @Override public void characters(char[] chars, int start, int length) { if (!stack.isEmpty()) stack.peek().text.append(chars, start, length); }
        @Override public void endElement(String uri, String local, String qName) { stack.pop(); }
        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException { throw new SAXException("XML_EXTERNAL_REFERENCE_BLOCKED"); }
        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException { throw new SAXException("XML_EXTERNAL_REFERENCE_BLOCKED"); }
        @Override public void error(SAXParseException ex) throws SAXException { throw ex; }
        @Override public void fatalError(SAXParseException ex) throws SAXException { throw ex; }
    }

    private SecureXml() { }
}
