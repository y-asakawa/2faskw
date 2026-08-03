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
package io.github.yasakawa.faskw.dashboard;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class CidrMatcher {

    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:]+");

    private record Network(byte[] address, int prefixLength) {
    }

    private final List<Network> networks;

    CidrMatcher(final String configured) {
        this(configured, "trusted proxy CIDR", false);
    }

    CidrMatcher(
            final String configured,
            final String propertyName,
            final boolean rejectUniversalNetwork) {
        networks = new ArrayList<>();
        for (String item : configured.split("[,\\s]+")) {
            if (!item.isBlank()) {
                networks.add(parse(item, propertyName, rejectUniversalNetwork));
            }
        }
        if (networks.isEmpty()) {
            throw new IllegalArgumentException(propertyName + " requires at least one CIDR");
        }
    }

    boolean matches(final InetAddress candidate) {
        final byte[] candidateBytes = candidate.getAddress();
        for (Network network : networks) {
            if (network.address().length != candidateBytes.length) {
                continue;
            }
            final int fullBytes = network.prefixLength() / 8;
            final int remainingBits = network.prefixLength() % 8;
            boolean matches = true;
            for (int i = 0; i < fullBytes; i++) {
                if (network.address()[i] != candidateBytes[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches && remainingBits > 0) {
                final int mask = 0xff << (8 - remainingBits);
                matches = (network.address()[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static Network parse(
            final String value,
            final String propertyName,
            final boolean rejectUniversalNetwork) {
        final String[] parts = value.trim().split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    propertyName + " requires a CIDR prefix: " + value);
        }
        try {
            final byte[] address = literalAddress(parts[0]).getAddress();
            final int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.length * 8) {
                throw new IllegalArgumentException(
                        propertyName + " has an invalid CIDR prefix: " + value);
            }
            if (rejectUniversalNetwork && prefix == 0) {
                throw new IllegalArgumentException(
                        propertyName + " must not allow an entire address family: " + value);
            }
            return new Network(address, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    propertyName + " has an invalid CIDR: " + value, e);
        }
    }

    private static InetAddress literalAddress(final String value) throws UnknownHostException {
        if (value.contains(":")) {
            if (!IPV6_LITERAL.matcher(value).matches()) {
                throw new UnknownHostException("IPv6 address must be a literal");
            }
            final InetAddress parsed = InetAddress.getByName(value);
            if (parsed.getAddress().length != 16) {
                throw new UnknownHostException("IPv6 address must be a literal");
            }
            return parsed;
        }

        final String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw new UnknownHostException("IPv4 address must contain four octets");
        }
        final byte[] address = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            if (!octets[i].matches("0|[1-9][0-9]{0,2}")) {
                throw new UnknownHostException("IPv4 address must be a literal");
            }
            final int octet;
            try {
                octet = Integer.parseInt(octets[i]);
            } catch (NumberFormatException e) {
                throw new UnknownHostException("IPv4 address must be a literal");
            }
            if (octet > 255) {
                throw new UnknownHostException("IPv4 octet is out of range");
            }
            address[i] = (byte) octet;
        }
        return InetAddress.getByAddress(address);
    }
}
