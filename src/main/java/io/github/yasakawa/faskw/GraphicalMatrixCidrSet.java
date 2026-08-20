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

import java.io.Serializable;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable IPv4/IPv6 CIDR set that accepts only address literals. */
final class GraphicalMatrixCidrSet implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int MAX_CIDRS = 256;
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private record Network(byte[] address, int prefixLength) implements Serializable {
        private static final long serialVersionUID = 1L;

        boolean contains(final byte[] candidate) {
            if (candidate.length != address.length) {
                return false;
            }
            final int fullBytes = prefixLength / 8;
            final int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            final int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }

    private final List<String> values;
    private final List<Network> networks;

    private GraphicalMatrixCidrSet(final List<String> values, final List<Network> networks) {
        this.values = List.copyOf(values);
        this.networks = List.copyOf(networks);
    }

    static GraphicalMatrixCidrSet parse(final String value, final String propertyName) {
        final String source = value == null ? "" : value.trim();
        if (source.isEmpty()) {
            return new GraphicalMatrixCidrSet(List.of(), List.of());
        }

        final Set<String> unique = new LinkedHashSet<>();
        for (final String token : source.split(",", -1)) {
            final String cidr = token.trim();
            if (cidr.isEmpty()) {
                throw invalid(propertyName, token, "contains an empty CIDR");
            }
            unique.add(cidr);
        }
        if (unique.size() > MAX_CIDRS) {
            throw new IllegalArgumentException(propertyName + " must contain at most "
                + MAX_CIDRS + " CIDRs");
        }

        final List<String> values = new ArrayList<>(unique.size());
        final List<Network> networks = new ArrayList<>(unique.size());
        for (final String cidr : unique) {
            networks.add(parseNetwork(cidr, propertyName));
            values.add(cidr);
        }
        return new GraphicalMatrixCidrSet(values, networks);
    }

    boolean contains(final String address) {
        final byte[] candidate = parseAddressLiteral(address, true);
        if (candidate == null) {
            return false;
        }
        for (final Network network : networks) {
            if (network.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    List<String> values() {
        return values;
    }

    private static Network parseNetwork(final String cidr, final String propertyName) {
        final int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw invalid(propertyName, cidr, "must use <IP address>/<prefix> format");
        }

        final String addressText = cidr.substring(0, slash).trim();
        final byte[] address = parseAddressLiteral(addressText, false);
        if (address == null) {
            throw invalid(propertyName, cidr, "contains an invalid IP address literal");
        }

        final int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException ex) {
            throw invalid(propertyName, cidr, "contains an invalid prefix length");
        }
        final int maximumPrefix = address.length * 8;
        if (prefixLength < 0 || prefixLength > maximumPrefix) {
            throw invalid(propertyName, cidr,
                "prefix length must be between 0 and " + maximumPrefix);
        }
        return new Network(mask(address, prefixLength), prefixLength);
    }

    private static byte[] parseAddressLiteral(final String input, final boolean allowIpv6Scope) {
        if (input == null) {
            return null;
        }
        String value = input.trim();
        if (value.isEmpty()) {
            return null;
        }
        final int scope = value.indexOf('%');
        if (scope >= 0) {
            if (!allowIpv6Scope || scope == 0 || scope == value.length() - 1
                    || value.indexOf('%', scope + 1) >= 0) {
                return null;
            }
            value = value.substring(0, scope);
        }
        if (value.indexOf(':') >= 0) {
            if (!IPV6_LITERAL.matcher(value).matches()) {
                return null;
            }
            try {
                final InetAddress parsed = InetAddress.getByName(value);
                return parsed instanceof Inet6Address ? parsed.getAddress() : null;
            } catch (UnknownHostException ex) {
                return null;
            }
        }
        return parseIpv4(value);
    }

    private static byte[] parseIpv4(final String value) {
        final String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        final byte[] address = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            if (octets[i].isEmpty() || !octets[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
            final int octet;
            try {
                octet = Integer.parseInt(octets[i]);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            address[i] = (byte) octet;
        }
        return address;
    }

    private static byte[] mask(final byte[] address, final int prefixLength) {
        final byte[] network = address.clone();
        final int fullBytes = prefixLength / 8;
        final int remainingBits = prefixLength % 8;
        if (remainingBits != 0) {
            network[fullBytes] &= (byte) (0xFF << (8 - remainingBits));
        }
        final int firstZeroByte = fullBytes + (remainingBits == 0 ? 0 : 1);
        for (int i = firstZeroByte; i < network.length; i++) {
            network[i] = 0;
        }
        return network;
    }

    private static IllegalArgumentException invalid(final String propertyName,
            final String value, final String reason) {
        return new IllegalArgumentException(propertyName + " " + reason + ": " + value);
    }
}
