package io.github.yasakawa.faskw.graphicalmatrix;

import java.io.FileInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class GraphicalMatrixApiConfig {
    private static final String DEFAULT_ALLOWED_CIDRS = "127.0.0.1/32,192.0.2.0/24";

    private final boolean enabled;
    private final String bearerToken;
    private final List<Cidr> allowedCidrs;
    private final int authFailureLimit;
    private final long authFailureWindowMillis;
    private final long authFailureLockMillis;
    private final boolean excludeSequences;
    private final boolean requireProtectedSequenceStorage;

    private GraphicalMatrixApiConfig(final boolean enabled, final String bearerToken,
            final List<Cidr> allowedCidrs, final int authFailureLimit,
            final long authFailureWindowMillis, final long authFailureLockMillis,
            final boolean excludeSequences, final boolean requireProtectedSequenceStorage) {
        this.enabled = enabled;
        this.bearerToken = bearerToken;
        this.allowedCidrs = allowedCidrs;
        this.authFailureLimit = authFailureLimit;
        this.authFailureWindowMillis = authFailureWindowMillis;
        this.authFailureLockMillis = authFailureLockMillis;
        this.excludeSequences = excludeSequences;
        this.requireProtectedSequenceStorage = requireProtectedSequenceStorage;
    }

    public static GraphicalMatrixApiConfig load(final String idpHome) {
        final Properties props = new Properties();
        final Path path = Path.of(idpHome, "conf", "graphicalmatrix", "api.properties");
        if (Files.isRegularFile(path)) {
            try (FileInputStream in = new FileInputStream(path.toFile())) {
                props.load(in);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to load API properties: " + path, ex);
            }
        }

        final boolean enabled = booleanProperty(props, "graphicalmatrix.api.enabled", false);
        final String bearerToken = token(props);
        final List<Cidr> cidrs = cidrs(props.getProperty(
            "graphicalmatrix.api.allowedCidrs", DEFAULT_ALLOWED_CIDRS));
        final int authFailureLimit = intProperty(props, "graphicalmatrix.api.authFailureLimit", 5);
        final long authFailureWindowMillis =
            secondsProperty(props, "graphicalmatrix.api.authFailureWindowSeconds", 60L) * 1000L;
        final long authFailureLockMillis =
            secondsProperty(props, "graphicalmatrix.api.authFailureLockSeconds", 300L) * 1000L;
        final boolean excludeSequences =
            booleanProperty(props, "graphicalmatrix.api.response.excludeSequences", true);
        final boolean requireProtectedSequenceStorage =
            booleanProperty(props, "graphicalmatrix.api.sequence.requireProtectedStorage", true);
        return new GraphicalMatrixApiConfig(enabled, bearerToken, cidrs,
            authFailureLimit, authFailureWindowMillis, authFailureLockMillis,
            excludeSequences, requireProtectedSequenceStorage);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isTokenConfigured() {
        return !bearerToken.isEmpty();
    }

    public boolean allowedIp(final String address) {
        if (allowedCidrs.isEmpty()) {
            return false;
        }
        try {
            final byte[] raw = InetAddress.getByName(address).getAddress();
            if (raw.length != 4) {
                return false;
            }
            final int value = ipv4(raw);
            for (final Cidr cidr : allowedCidrs) {
                if (cidr.matches(value)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean validBearer(final String authorizationHeader) {
        if (bearerToken.isEmpty() || authorizationHeader == null) {
            return false;
        }
        final String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        final String presented = authorizationHeader.substring(prefix.length()).trim();
        return MessageDigest.isEqual(
            bearerToken.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    public int authFailureLimit() {
        return authFailureLimit;
    }

    public long authFailureWindowMillis() {
        return authFailureWindowMillis;
    }

    public long authFailureLockMillis() {
        return authFailureLockMillis;
    }

    public boolean rateLimitEnabled() {
        return authFailureLimit > 0 && authFailureWindowMillis > 0L && authFailureLockMillis > 0L;
    }

    public boolean excludeSequences() {
        return excludeSequences;
    }

    public boolean requireProtectedSequenceStorage() {
        return requireProtectedSequenceStorage;
    }

    private static String token(final Properties props) {
        final String direct = trim(props.getProperty("graphicalmatrix.api.bearerToken", ""));
        if (!direct.isEmpty()) {
            return direct;
        }

        final String tokenFile = trim(props.getProperty("graphicalmatrix.api.bearerTokenFile", ""));
        if (tokenFile.isEmpty()) {
            return "";
        }

        try {
            return Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read API bearer token file: " + tokenFile, ex);
        }
    }

    private static List<Cidr> cidrs(final String value) {
        final List<Cidr> out = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return out;
        }
        for (final String raw : value.split(",")) {
            final String item = raw.trim();
            if (!item.isEmpty()) {
                out.add(Cidr.parse(item));
            }
        }
        return out;
    }

    private static boolean booleanProperty(final Properties props, final String key,
            final boolean defaultValue) {
        final String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        final String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
            || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static int intProperty(final Properties props, final String key, final int defaultValue) {
        final String value = trim(props.getProperty(key, ""));
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long secondsProperty(final Properties props, final String key, final long defaultValue) {
        final String value = trim(props.getProperty(key, ""));
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static int ipv4(final byte[] raw) {
        return ((raw[0] & 0xff) << 24)
            | ((raw[1] & 0xff) << 16)
            | ((raw[2] & 0xff) << 8)
            | (raw[3] & 0xff);
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }

    private static final class Cidr {
        private final int network;
        private final int mask;

        private Cidr(final int network, final int mask) {
            this.network = network;
            this.mask = mask;
        }

        static Cidr parse(final String value) {
            final String[] parts = value.split("/", 2);
            final String address = parts[0].trim();
            final int prefix = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 32;
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + value);
            }
            try {
                final byte[] raw = InetAddress.getByName(address).getAddress();
                if (raw.length != 4) {
                    throw new IllegalArgumentException("Only IPv4 CIDR is supported: " + value);
                }
                final int mask = prefix == 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
                return new Cidr(ipv4(raw) & mask, mask);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid CIDR: " + value, ex);
            }
        }

        boolean matches(final int address) {
            return (address & mask) == network;
        }
    }
}
