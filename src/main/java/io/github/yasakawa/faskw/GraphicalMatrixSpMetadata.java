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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class GraphicalMatrixSpMetadata {
    private static final String MD_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
    private static final int MAX_ELEMENTS = 10_000;
    private static final int MAX_CERTIFICATES = 16;
    private static final int MAX_URL_LENGTH = 2048;

    record Parsed(byte[] bytes, String source, String entityId, List<String> acsUrls,
                  List<String> certificateFingerprints, String sha256, List<String> warnings) {
    }

    private GraphicalMatrixSpMetadata() {
    }

    static Parsed fromFile(final GraphicalMatrixSpManagementConfig config, final Path source,
            final String expectedEntityId) throws Exception {
        final Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("metadata file must be a regular non-symlink file: " + source);
        }
        final byte[] bytes = readLimited(Files.newInputStream(normalized), config.maxMetadataBytes());
        return parse(config, bytes, normalized.toString(), null, expectedEntityId);
    }

    static Parsed fromUrl(final GraphicalMatrixSpManagementConfig config, final URI source,
            final String expectedEntityId) throws Exception {
        final InetAddress[] addresses = validateMetadataUri(config, source);
        final String sourceHost = source.getHost().toLowerCase(Locale.ROOT);
        final DnsResolver pinnedResolver = requestedHost -> {
            if (!sourceHost.equalsIgnoreCase(requestedHost)) {
                throw new UnknownHostException("metadata request attempted an unapproved host: "
                    + requestedHost);
            }
            return addresses.clone();
        };
        final RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(timeoutMillis(config.connectTimeout().toMillis()))
            .setSocketTimeout(timeoutMillis(config.readTimeout().toMillis()))
            .setConnectionRequestTimeout(timeoutMillis(config.connectTimeout().toMillis()))
            .setRedirectsEnabled(false)
            .build();
        final HttpGet request = new HttpGet(source);
        request.setHeader("Accept", "application/samlmetadata+xml, application/xml, text/xml");
        final byte[] bytes;
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setDnsResolver(pinnedResolver)
                .disableRedirectHandling()
                .build();
             CloseableHttpResponse response = client.execute(request)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new IOException("metadata endpoint returned HTTP "
                    + response.getStatusLine().getStatusCode());
            }
            final HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new IOException("metadata endpoint returned an empty response");
            }
            bytes = readLimited(entity.getContent(), config.maxMetadataBytes());
        }
        return parse(config, bytes, source.toString(), sourceHost, expectedEntityId);
    }

    static Parsed parseExisting(final GraphicalMatrixSpManagementConfig config, final Path source)
            throws Exception {
        return fromFile(config, source, null);
    }

    static Parsed inspectExisting(final GraphicalMatrixSpManagementConfig config, final Path source)
            throws Exception {
        final Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("metadata file must be a regular non-symlink file: " + source);
        }
        final byte[] bytes = readLimited(Files.newInputStream(normalized), config.maxMetadataBytes());
        return parse(config, bytes, normalized.toString(), null, null, false);
    }

    static String standardFileName(final String entityId) {
        return hex(digest("SHA-1", entityId.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".xml";
    }

    private static Parsed parse(final GraphicalMatrixSpManagementConfig config, final byte[] bytes,
            final String source, final String sourceHost, final String expectedEntityId) throws Exception {
        return parse(config, bytes, source, sourceHost, expectedEntityId, true);
    }

    private static Parsed parse(final GraphicalMatrixSpManagementConfig config, final byte[] bytes,
            final String source, final String sourceHost, final String expectedEntityId,
            final boolean enforceAcsAllowList) throws Exception {
        rejectXmlPreamble(bytes);
        final DocumentBuilderFactory factory = secureFactory();
        final Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        final Element root = document.getDocumentElement();
        if (root == null || !"EntityDescriptor".equals(root.getLocalName())
                || !MD_NS.equals(root.getNamespaceURI())) {
            throw new IllegalArgumentException("metadata root must be one SAML EntityDescriptor");
        }
        if (document.getElementsByTagNameNS("*", "*").getLength() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("metadata contains too many XML elements");
        }
        final String entityId = required(root.getAttribute("entityID"), "metadata entityID");
        validateEntityId(entityId);
        if (expectedEntityId != null && !expectedEntityId.equals(entityId)) {
            throw new IllegalArgumentException("metadata entityID does not match --entity-id");
        }

        final NodeList spDescriptors = root.getElementsByTagNameNS(MD_NS, "SPSSODescriptor");
        if (spDescriptors.getLength() != 1) {
            throw new IllegalArgumentException("metadata must contain exactly one SPSSODescriptor");
        }
        final Element descriptor = (Element) spDescriptors.item(0);
        final List<String> acsUrls = acsUrls(config, descriptor, sourceHost, enforceAcsAllowList);
        final List<String> fingerprints = certificateFingerprints(descriptor);
        final List<String> warnings = new ArrayList<>();
        if (fingerprints.isEmpty()) {
            warnings.add("metadata does not contain an X.509 signing/encryption certificate");
        }
        final String signed = descriptor.getAttribute("AuthnRequestsSigned");
        if (!"true".equalsIgnoreCase(signed)) {
            warnings.add("AuthnRequestsSigned is not true");
        }
        if (root.getAttribute("validUntil").isBlank()) {
            warnings.add("metadata validUntil is not set");
        }
        if (document.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "Signature").getLength() == 0
                && document.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature")
                    .getLength() == 0) {
            warnings.add("metadata XML signature is not present; verify the SHA-256 out of band");
        }
        return new Parsed(bytes.clone(), source, entityId, List.copyOf(acsUrls),
            List.copyOf(fingerprints), hex(digest("SHA-256", bytes)), List.copyOf(warnings));
    }

    private static List<String> acsUrls(final GraphicalMatrixSpManagementConfig config,
            final Element descriptor, final String sourceHost,
            final boolean enforceAllowList) throws Exception {
        final NodeList nodes = descriptor.getElementsByTagNameNS(MD_NS, "AssertionConsumerService");
        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException("metadata has no AssertionConsumerService");
        }
        final Set<String> allowedHosts = new LinkedHashSet<>(config.allowedAcsHosts());
        if (sourceHost != null) {
            allowedHosts.add(sourceHost.toLowerCase(Locale.ROOT));
        }
        final List<String> urls = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element element = (Element) nodes.item(i);
            final String location = required(element.getAttribute("Location"), "ACS Location");
            if (location.length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException("ACS URL is too long");
            }
            final URI uri = URI.create(location);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("ACS must be an HTTPS URL without credentials or fragment: "
                    + location);
            }
            final String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (enforceAllowList && !allowedHosts.contains(host)) {
                throw new IllegalArgumentException("ACS host is not approved: " + host
                    + "; add it to graphicalmatrix.sp.metadata.allowedAcsHosts");
            }
            urls.add(location);
        }
        return urls;
    }

    private static List<String> certificateFingerprints(final Element descriptor) throws Exception {
        final NodeList nodes = descriptor.getElementsByTagNameNS(
            "http://www.w3.org/2000/09/xmldsig#", "X509Certificate");
        if (nodes.getLength() > MAX_CERTIFICATES) {
            throw new IllegalArgumentException("metadata contains too many X.509 certificates");
        }
        final Set<String> fingerprints = new LinkedHashSet<>();
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            final String encoded = node.getTextContent().replaceAll("\\s+", "");
            if (encoded.isEmpty()) {
                throw new IllegalArgumentException("metadata contains an empty X509Certificate");
            }
            final byte[] der = Base64.getDecoder().decode(encoded);
            final X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(der));
            fingerprints.add(colonHex(digest("SHA-256", certificate.getEncoded())));
        }
        return new ArrayList<>(fingerprints);
    }

    private static InetAddress[] validateMetadataUri(final GraphicalMatrixSpManagementConfig config,
            final URI uri) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("metadata URL must use HTTPS with a host");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("metadata URL must not contain credentials, query, or fragment");
        }
        final String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!config.allowedMetadataHosts().contains(host)) {
            throw new IllegalArgumentException("metadata host is not allow-listed: " + host);
        }
        if (isIpLiteral(host)) {
            throw new IllegalArgumentException("metadata URL host must be an allow-listed FQDN, not an IP literal");
        }
        final InetAddress[] addresses = InetAddress.getAllByName(host);
        validateResolvedAddresses(host, addresses);
        return addresses;
    }

    static void validateResolvedAddresses(final String host, final InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("metadata host did not resolve: " + host);
        }
        for (final InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IllegalArgumentException("metadata host resolves to a prohibited address: "
                    + address.getHostAddress());
            }
        }
    }

    private static boolean isPublicAddress(final InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        final byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            final int first = bytes[0] & 0xff;
            final int second = bytes[1] & 0xff;
            final int third = bytes[2] & 0xff;
            return first != 0 && first != 10 && first != 127 && first < 224
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 0 && third == 0)
                && !(first == 192 && second == 0 && third == 2)
                && !(first == 192 && second == 168)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113);
        }
        final int first = bytes[0] & 0xff;
        final int second = bytes[1] & 0xff;
        final int third = bytes[2] & 0xff;
        final int fourth = bytes[3] & 0xff;
        return !(first == 0x00 || first == 0x01)
            && !(first == 0x20 && second == 0x01
                && ((third & 0xfe) == 0x00 || (third == 0x0d && fourth == 0xb8)))
            && !(first == 0x20 && second == 0x02)
            && first < 0xfc;
    }

    private static int timeoutMillis(final long milliseconds) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, milliseconds));
    }

    private static boolean isIpLiteral(final String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    private static void validateEntityId(final String entityId) {
        if (entityId.length() > MAX_URL_LENGTH || entityId.chars().anyMatch(Character::isISOControl)
                || entityId.chars().anyMatch(Character::isWhitespace)
                || entityId.indexOf(',') >= 0 || entityId.indexOf(';') >= 0
                || entityId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("metadata entityID is invalid");
        }
        final URI uri = URI.create(entityId);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("metadata entityID must be an absolute URI");
        }
    }

    private static byte[] readLimited(final InputStream input, final int maximum) throws IOException {
        try (InputStream closeable = input) {
            final byte[] bytes = closeable.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IOException("metadata exceeds maximum size of " + maximum + " bytes");
            }
            return bytes;
        }
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

    private static void rejectXmlPreamble(final byte[] bytes) {
        final String head = new String(bytes, 0, Math.min(bytes.length, 1024),
            java.nio.charset.StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
            throw new IllegalArgumentException("DTD and entity declarations are prohibited in metadata");
        }
    }

    private static String required(final String value, final String name) {
        final String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return trimmed;
    }

    private static byte[] digest(final String algorithm, final byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (Exception ex) {
            throw new IllegalStateException(algorithm + " is unavailable", ex);
        }
    }

    private static String hex(final byte[] value) {
        final StringBuilder out = new StringBuilder(value.length * 2);
        for (final byte item : value) {
            out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return out.toString();
    }

    private static String colonHex(final byte[] value) {
        final StringBuilder out = new StringBuilder(value.length * 3 - 1);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format(Locale.ROOT, "%02X", value[i] & 0xff));
        }
        return out.toString();
    }
}
