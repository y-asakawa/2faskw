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
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Explicitly requested unsigned snapshots, never a signature verification or filter engine. */
final class MetadataSnapshots {
    static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    record Snapshot(byte[] bytes, List<String> facts) { }
    static final class Unsupported extends Exception {
        final String code;
        final int ancestorDepth;
        Unsupported(String code, String message) { this(code, message, 0); }
        Unsupported(String code, String message, int ancestorDepth) {
            super(message); this.code = code; this.ancestorDepth = ancestorDepth;
        }
    }
    private final Document document;
    private final DocumentBuilderFactory factory;

    MetadataSnapshots(byte[] source, String location) throws Exception {
        SecureXml.parse(source, location);
        factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(source));
    }

    Snapshot extract(int ordinal) throws Exception {
        return extract(ordinal, false);
    }

    Snapshot extract(int ordinal, boolean spRolesOnly) throws Exception {
        return extract(ordinal, spRolesOnly, Instant.now());
    }

    Snapshot extract(int ordinal, boolean spRolesOnly, Instant checkedAt) throws Exception {
        Element entity = (Element) document.getElementsByTagNameNS(SecureXml.MD, "EntityDescriptor").item(ordinal);
        if (entity == null) throw new Unsupported("METADATA_EXTRACTION_FAILED", "Entity ordinal is missing.");
        List<Element> ancestry = new ArrayList<>();
        for (org.w3c.dom.Node parent = entity; parent instanceof Element e; parent = parent.getParentNode()) {
            if (e != entity && (!SecureXml.MD.equals(e.getNamespaceURI()) || !e.getLocalName().equals("EntitiesDescriptor")))
                throw new Unsupported("METADATA_PARENT_CONTEXT_REQUIRED", "Entity is not directly enclosed by SAML EntitiesDescriptor groups.");
            ancestry.addFirst(e);
        }
        Instant expiry = null;
        Duration cache = null;
        List<String> facts = new ArrayList<>();
        int signatures = 0;
        for (Element e : ancestry) {
            if (e.hasAttributeNS(XMLConstants.XML_NS_URI, "base"))
                throw new Unsupported("METADATA_PARENT_CONTEXT_REQUIRED", "xml:base requires explicit effective endpoint resolution; extraction is held.");
            String validity = e.getAttribute("validUntil");
            String duration = e.getAttribute("cacheDuration");
            String reason = validityProblem(validity, duration, checkedAt);
            if (reason != null) throw new Unsupported("METADATA_EFFECTIVE_EXPIRED",
                "reason=" + reason + "; scope=" + e.getLocalName() + "; name=" + SafeFiles.display(e.getAttribute("Name"))
                    + "; " + validityEvidence(validity, duration, checkedAt), ancestry.size() - 1 - ancestry.indexOf(e));
            if (!validity.isEmpty()) {
                Instant value = Instant.parse(validity);
                if (expiry == null || value.isBefore(expiry)) expiry = value;
            }
            if (!duration.isEmpty()) {
                Duration value = Duration.parse(duration);
                if (cache == null || value.compareTo(cache) < 0) cache = value;
            }
            if (e != entity) {
                facts.add("parent_group=" + e.getAttribute("Name") + " validUntil=" + validity + " cacheDuration=" + duration);
                var attrs = e.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    var attr = attrs.item(i);
                    if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())
                            && !(attr.getNamespaceURI() == null && java.util.Set.of("ID", "Name", "validUntil", "cacheDuration").contains(attr.getNodeName()))
                            && !(XMLConstants.XML_NS_URI.equals(attr.getNamespaceURI()) && attr.getLocalName().equals("lang"))
                            && !(SecureXml.XSI.equals(attr.getNamespaceURI()) && attr.getLocalName().equals("schemaLocation")))
                        throw new Unsupported("METADATA_PARENT_CONTEXT_REQUIRED", "Unknown parent group attribute: " + attr.getNodeName());
                }
                for (org.w3c.dom.Node child = e.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element el) {
                    if (DS.equals(el.getNamespaceURI()) && el.getLocalName().equals("Signature")) signatures++;
                    else if (!SecureXml.MD.equals(el.getNamespaceURI()) || !java.util.Set.of("EntityDescriptor", "EntitiesDescriptor").contains(el.getLocalName()))
                        throw new Unsupported("METADATA_PARENT_CONTEXT_REQUIRED", "Parent Extensions/unknown elements are not safely representable in an independent entity.");
                } else if (child instanceof org.w3c.dom.Text && !child.getTextContent().isBlank())
                    throw new Unsupported("METADATA_PARENT_CONTEXT_REQUIRED", "Non-whitespace parent group text is not valid metadata context.");
            }
        }
        Document out = factory.newDocumentBuilder().newDocument();
        Element copy = (Element) out.importNode(entity, true);
        out.appendChild(copy);
        if (spRolesOnly) {
            int removed = 0;
            for (org.w3c.dom.Node child = copy.getFirstChild(); child != null;) {
                org.w3c.dom.Node next = child.getNextSibling();
                if (child instanceof Element e && SecureXml.MD.equals(e.getNamespaceURI())
                        && java.util.Set.of("RoleDescriptor", "IDPSSODescriptor", "AuthnAuthorityDescriptor", "AttributeAuthorityDescriptor", "PDPDescriptor").contains(e.getLocalName())) {
                    copy.removeChild(e); removed++;
                }
                child = next;
            }
            facts.add("SPSSODescriptor_only_role_snapshot=true; other_roles_removed=" + removed + "; filter order and source trust require review");
        }
        // Preserve inherited namespace bindings, including QName-valued extension attributes/text.
        for (Element e : ancestry) {
            var attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                var attr = attrs.item(i);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()))
                    copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attr.getNodeName(), attr.getNodeValue());
            }
            if (e.hasAttributeNS(XMLConstants.XML_NS_URI, "lang"))
                copy.setAttributeNS(XMLConstants.XML_NS_URI, "xml:lang", e.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
        }
        var signed = copy.getElementsByTagNameNS(DS, "Signature");
        while (signed.getLength() > 0) {
            org.w3c.dom.Node signature = signed.item(0);
            signature.getParentNode().removeChild(signature); signatures++;
        }
        if (cache != null) {
            copy.setAttribute("cacheDuration", cache.toString());
            Instant snapshotLimit = checkedAt.plus(cache);
            if (expiry == null || snapshotLimit.isBefore(expiry)) expiry = snapshotLimit;
            facts.add("snapshot_expiry_capped_by_cache_duration=" + cache + " (conservative static-snapshot bound)");
        }
        if (expiry != null) copy.setAttribute("validUntil", expiry.toString());
        facts.add("snapshot_effective_validUntil=" + (expiry == null ? "UNSPECIFIED; administrator must set refresh policy" : expiry));
        facts.add("source_signature_count=" + signatures + "; snapshot=UNSIGNED; original source must be verified independently");
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(out), new StreamResult(bytes));
        SecureXml.parse(bytes.toByteArray(), "extracted metadata");
        return new Snapshot(bytes.toByteArray(), List.copyOf(facts));
    }

    static String validityProblem(String validity, String duration, Instant checkedAt) {
        if (!validity.isEmpty()) {
            try { if (!Instant.parse(validity).isAfter(checkedAt)) return "VALID_UNTIL_EXPIRED"; }
            catch (RuntimeException ex) { return "VALID_UNTIL_MALFORMED"; }
        }
        if (!duration.isEmpty()) {
            try {
                Duration value = Duration.parse(duration);
                if (value.isNegative() || value.isZero()) return "CACHE_DURATION_NONPOSITIVE";
                checkedAt.plus(value);
            } catch (RuntimeException ex) { return "CACHE_DURATION_UNSUPPORTED"; }
        }
        return null;
    }

    static String validityEvidence(String validity, String duration, Instant checkedAt) {
        return "validUntil=" + (validity.isEmpty() ? "<missing>" : SafeFiles.display(validity))
            + "; cacheDuration=" + (duration.isEmpty() ? "<missing>" : SafeFiles.display(duration))
            + "; checked_at=" + checkedAt;
    }
}
