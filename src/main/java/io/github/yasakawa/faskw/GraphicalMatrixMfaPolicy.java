package io.github.yasakawa.faskw;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Parses and evaluates the ordered SP/network MFA policy. */
final class GraphicalMatrixMfaPolicy {
    static final String FORCE_SPS = "forceSPs";
    static final String BYPASS_SPS = "bypassSPs";
    static final String BYPASS_SP_CIDRS = "bypassSpCidrs";
    static final String BYPASS_NETWORK = "bypassNetwork";
    static final String REQUIRED_SPS = "requiredSPs";
    static final String DEFAULT = "default";

    static final List<String> DEFAULT_ORDER = List.of(
        FORCE_SPS,
        BYPASS_SPS,
        BYPASS_SP_CIDRS,
        BYPASS_NETWORK,
        REQUIRED_SPS,
        DEFAULT
    );

    private static final Set<String> ALLOWED_RULES = Set.copyOf(DEFAULT_ORDER);

    enum Outcome {
        REQUIRE,
        BYPASS
    }

    record Decision(Outcome outcome, String rule) {
    }

    private record Ipv4Cidr(int network, int mask) {
        boolean matches(final int address) {
            return (address & mask) == network;
        }
    }

    private record SpCidrRule(String relyingPartyId, List<Ipv4Cidr> cidrs) {
    }

    private record ClientAddress(Integer ipv4, String key) {
    }

    private final String defaultPolicy;
    private final List<String> order;
    private final Set<String> forceSPs;
    private final Set<String> bypassSPs;
    private final List<SpCidrRule> bypassSpCidrs;
    private final Set<String> requiredSPs;
    private final Set<String> bypassIPs;
    private final List<Ipv4Cidr> bypassCIDRs;

    private GraphicalMatrixMfaPolicy(final String defaultPolicy, final List<String> order,
            final Set<String> forceSPs, final Set<String> bypassSPs,
            final List<SpCidrRule> bypassSpCidrs, final Set<String> requiredSPs,
            final Set<String> bypassIPs, final List<Ipv4Cidr> bypassCIDRs) {
        this.defaultPolicy = defaultPolicy;
        this.order = List.copyOf(order);
        this.forceSPs = Set.copyOf(forceSPs);
        this.bypassSPs = Set.copyOf(bypassSPs);
        this.bypassSpCidrs = List.copyOf(bypassSpCidrs);
        this.requiredSPs = Set.copyOf(requiredSPs);
        this.bypassIPs = Set.copyOf(bypassIPs);
        this.bypassCIDRs = List.copyOf(bypassCIDRs);
    }

    static GraphicalMatrixMfaPolicy parse(final Properties properties) {
        final Properties source = properties != null ? properties : new Properties();
        final String defaultPolicy = trim(source.getProperty("graphicalmatrix.mfa.default", "require"))
            .toLowerCase(Locale.ROOT);
        if (!"require".equals(defaultPolicy) && !"bypass".equals(defaultPolicy)) {
            throw new IllegalArgumentException(
                "graphicalmatrix.mfa.default must be require or bypass: " + defaultPolicy);
        }

        final List<String> order = parseOrder(
            source.getProperty("graphicalmatrix.mfa.policyOrder"));
        final Set<String> forceSPs = csv(source.getProperty("graphicalmatrix.mfa.forceSPs"));
        final Set<String> bypassSPs = csv(source.getProperty("graphicalmatrix.mfa.bypassSPs"));
        final List<SpCidrRule> bypassSpCidrs = parseSpCidrRules(
            source.getProperty("graphicalmatrix.mfa.bypassSpCidrs"));
        final Set<String> requiredSPs = csv(source.getProperty("graphicalmatrix.mfa.requiredSPs"));
        validateBoolean(source, "graphicalmatrix.mfa.useForwardedFor", "false");

        final Set<String> bypassIPs = new LinkedHashSet<>();
        for (final String ip : csv(source.getProperty("graphicalmatrix.mfa.bypassIPs"))) {
            bypassIPs.add(parseIpLiteral(ip).key());
        }

        final List<Ipv4Cidr> bypassCIDRs = new ArrayList<>();
        for (final String cidr : csv(source.getProperty("graphicalmatrix.mfa.bypassCIDRs"))) {
            bypassCIDRs.add(parseCidr(cidr));
        }

        return new GraphicalMatrixMfaPolicy(defaultPolicy, order, forceSPs, bypassSPs,
            bypassSpCidrs, requiredSPs, bypassIPs, bypassCIDRs);
    }

    Decision evaluate(final String relyingPartyId, final String clientIp) {
        final String sp = trim(relyingPartyId);
        final ClientAddress ip = parseClientIp(clientIp);
        for (final String rule : order) {
            final Decision decision = switch (rule) {
                case FORCE_SPS -> forceSPs.contains(sp)
                    ? new Decision(Outcome.REQUIRE, rule) : null;
                case BYPASS_SPS -> bypassSPs.contains(sp)
                    ? new Decision(Outcome.BYPASS, rule) : null;
                case BYPASS_SP_CIDRS -> matchesSpCidr(sp, ip)
                    ? new Decision(Outcome.BYPASS, rule) : null;
                case BYPASS_NETWORK -> matchesNetwork(ip)
                    ? new Decision(Outcome.BYPASS, rule) : null;
                case REQUIRED_SPS -> requiredSPs.isEmpty() ? null
                    : new Decision(requiredSPs.contains(sp) ? Outcome.REQUIRE : Outcome.BYPASS, rule);
                case DEFAULT -> new Decision(
                    "require".equals(defaultPolicy) ? Outcome.REQUIRE : Outcome.BYPASS, rule);
                default -> throw new IllegalStateException("unsupported MFA policy rule: " + rule);
            };
            if (decision != null) {
                return decision;
            }
        }
        throw new IllegalStateException("MFA policy did not produce a decision");
    }

    String orderText() {
        return String.join(",", order);
    }

    String defaultPolicy() {
        return defaultPolicy;
    }

    Set<String> forceSPs() {
        return forceSPs;
    }

    Set<String> bypassSPs() {
        return bypassSPs;
    }

    Set<String> requiredSPs() {
        return requiredSPs;
    }

    Set<String> bypassSpEntityIds() {
        final Set<String> out = new LinkedHashSet<>();
        for (final SpCidrRule rule : bypassSpCidrs) {
            out.add(rule.relyingPartyId());
        }
        return out;
    }

    static boolean spCidrMatches(final String rules, final String relyingPartyId, final String ip) {
        try {
            final List<SpCidrRule> parsed = parseSpCidrRules(rules);
            final ClientAddress address = parseClientIp(ip);
            if (address == null) {
                return false;
            }
            for (final SpCidrRule rule : parsed) {
                if (!rule.relyingPartyId().equals(trim(relyingPartyId))) {
                    continue;
                }
                for (final Ipv4Cidr cidr : rule.cidrs()) {
                    if (address.ipv4() != null && cidr.matches(address.ipv4().intValue())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean matchesSpCidr(final String relyingPartyId, final ClientAddress address) {
        if (address == null || address.ipv4() == null) {
            return false;
        }
        for (final SpCidrRule rule : bypassSpCidrs) {
            if (!rule.relyingPartyId().equals(relyingPartyId)) {
                continue;
            }
            for (final Ipv4Cidr cidr : rule.cidrs()) {
                if (cidr.matches(address.ipv4().intValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesNetwork(final ClientAddress address) {
        if (address == null) {
            return false;
        }
        if (bypassIPs.contains(address.key())) {
            return true;
        }
        if (address.ipv4() == null) {
            return false;
        }
        for (final Ipv4Cidr cidr : bypassCIDRs) {
            if (cidr.matches(address.ipv4().intValue())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parseOrder(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_ORDER;
        }

        final List<String> order = new ArrayList<>();
        for (final String token : value.split(",", -1)) {
            final String rule = trim(token);
            if (rule.isEmpty()) {
                throw new IllegalArgumentException("graphicalmatrix.mfa.policyOrder contains an empty rule");
            }
            if (!ALLOWED_RULES.contains(rule)) {
                throw new IllegalArgumentException(
                    "graphicalmatrix.mfa.policyOrder contains unknown rule: " + rule);
            }
            if (order.contains(rule)) {
                throw new IllegalArgumentException(
                    "graphicalmatrix.mfa.policyOrder contains duplicate rule: " + rule);
            }
            order.add(rule);
        }

        if (order.size() != DEFAULT_ORDER.size() || !order.containsAll(DEFAULT_ORDER)) {
            final Set<String> missing = new LinkedHashSet<>(DEFAULT_ORDER);
            missing.removeAll(order);
            throw new IllegalArgumentException(
                "graphicalmatrix.mfa.policyOrder must contain every rule exactly once; missing=" + missing);
        }
        if (!DEFAULT.equals(order.get(order.size() - 1))) {
            throw new IllegalArgumentException("graphicalmatrix.mfa.policyOrder must end with default");
        }
        return List.copyOf(order);
    }

    private static List<SpCidrRule> parseSpCidrRules(final String value) {
        final List<SpCidrRule> rules = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return rules;
        }

        for (final String rawRule : value.split(";", -1)) {
            final String text = trim(rawRule);
            final int separator = text.indexOf('|');
            if (text.isEmpty() || separator <= 0 || separator != text.lastIndexOf('|')) {
                throw new IllegalArgumentException(
                    "graphicalmatrix.mfa.bypassSpCidrs rule must be <SP entityID>|<CIDR>[,<CIDR>]: "
                        + text);
            }
            final String relyingPartyId = trim(text.substring(0, separator));
            final String cidrText = trim(text.substring(separator + 1));
            if (relyingPartyId.isEmpty() || cidrText.isEmpty()) {
                throw new IllegalArgumentException(
                    "graphicalmatrix.mfa.bypassSpCidrs rule has an empty SP or CIDR: " + text);
            }

            final List<Ipv4Cidr> cidrs = new ArrayList<>();
            for (final String rawCidr : cidrText.split(",", -1)) {
                final String cidr = trim(rawCidr);
                if (cidr.isEmpty()) {
                    throw new IllegalArgumentException(
                        "graphicalmatrix.mfa.bypassSpCidrs contains an empty CIDR: " + text);
                }
                cidrs.add(parseCidr(cidr));
            }
            rules.add(new SpCidrRule(relyingPartyId, List.copyOf(cidrs)));
        }
        return List.copyOf(rules);
    }

    private static Ipv4Cidr parseCidr(final String value) {
        final String[] parts = trim(value).split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid IPv4 CIDR: " + value);
        }
        final int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid IPv4 CIDR prefix: " + value, ex);
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("IPv4 CIDR prefix must be 0-32: " + value);
        }
        final int address = parseIpv4(parts[0]);
        final int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
        return new Ipv4Cidr(address & mask, mask);
    }

    private static void validateBoolean(final Properties properties, final String name,
            final String defaultValue) {
        final String value = trim(properties.getProperty(name, defaultValue)).toLowerCase(Locale.ROOT);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException(name + " must be true or false: " + value);
        }
    }

    private static ClientAddress parseClientIp(final String value) {
        try {
            return parseIpLiteral(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ClientAddress parseIpLiteral(final String value) {
        final String text = trim(value);
        if (!text.contains(":")) {
            final int ipv4 = parseIpv4(text);
            return new ClientAddress(Integer.valueOf(ipv4), "ipv4:" + Integer.toUnsignedString(ipv4));
        }
        if (text.contains("%")) {
            throw new IllegalArgumentException("scoped IPv6 address is not supported: " + value);
        }
        try {
            final byte[] address = InetAddress.getByName(text).getAddress();
            if (address.length == 4) {
                final int ipv4 = bytesToIpv4(address);
                return new ClientAddress(Integer.valueOf(ipv4),
                    "ipv4:" + Integer.toUnsignedString(ipv4));
            }
            if (address.length != 16) {
                throw new IllegalArgumentException("invalid IP address: " + value);
            }
            return new ClientAddress(null, "ipv6:" + HexFormat.of().formatHex(address));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid IP address: " + value, ex);
        }
    }

    private static int bytesToIpv4(final byte[] bytes) {
        return ((bytes[0] & 0xff) << 24)
            | ((bytes[1] & 0xff) << 16)
            | ((bytes[2] & 0xff) << 8)
            | (bytes[3] & 0xff);
    }

    private static int parseIpv4(final String value) {
        final String text = trim(value);
        final String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("invalid IPv4 address: " + value);
        }
        int result = 0;
        for (final String octet : octets) {
            if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("invalid IPv4 address: " + value);
            }
            final int number;
            try {
                number = Integer.parseInt(octet);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid IPv4 address: " + value, ex);
            }
            if (number < 0 || number > 255) {
                throw new IllegalArgumentException("invalid IPv4 address: " + value);
            }
            result = (result << 8) | number;
        }
        return result;
    }

    private static Set<String> csv(final String value) {
        final Set<String> out = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) {
            return out;
        }
        for (final String raw : value.split(",", -1)) {
            final String item = trim(raw);
            if (item.isEmpty()) {
                throw new IllegalArgumentException("MFA policy list contains an empty value");
            }
            out.add(item);
        }
        return out;
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
