package io.github.yasakawa.faskw;

import java.io.FileInputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import net.shibboleth.profile.context.RelyingPartyContext;
import net.shibboleth.shared.servlet.impl.HttpServletRequestResponseContext;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GraphicalMatrixMfaDecisionStrategy implements Function<ProfileRequestContext, String> {
    private static final Logger LOG = LoggerFactory.getLogger(GraphicalMatrixMfaDecisionStrategy.class);
    private static final String EXTERNAL_FLOW = "authn/External";
    private static final String TOTP_FLOW = "authn/TOTP";
    private static final String WEBAUTHN_FLOW = "authn/WebAuthn";

    public void initialize() {
    }

    public void destroy() {
    }

    @Override
    public String apply(final ProfileRequestContext input) {
        final Properties policy = loadPolicy();
        final String relyingPartyId = relyingPartyId(input);
        final String clientIp = clientIp(policy);

        if (isSelfServiceProfile(input)) {
            LOG.info("MFA required for 2FAS-KW self-service profile: ip={}", clientIp);
            return selectFlow(input, GraphicalMatrixSelfServiceAuthentication.PROFILE_ID, clientIp);
        }

        if (contains(csv(policy.getProperty("graphicalmatrix.mfa.bypassSPs")), relyingPartyId)) {
            LOG.info("MFA bypassed by SP policy: {}", relyingPartyId);
            return null;
        }

        if (ipMatches(policy, clientIp)) {
            LOG.info("MFA bypassed by IP policy: {}", clientIp);
            return null;
        }

        final Set<String> requiredSPs = csv(policy.getProperty("graphicalmatrix.mfa.requiredSPs"));
        if (!requiredSPs.isEmpty()) {
            final boolean required = contains(requiredSPs, relyingPartyId);
            LOG.info("MFA SP required-list decision: sp={}, required={}", relyingPartyId, required);
            return required ? selectFlow(input, relyingPartyId, clientIp) : null;
        }

        final String defaultPolicy = trim(policy.getProperty("graphicalmatrix.mfa.default", "require"));
        final boolean requireByDefault = !"bypass".equalsIgnoreCase(defaultPolicy);
        LOG.info("MFA default decision: sp={}, ip={}, required={}",
            relyingPartyId, clientIp, requireByDefault);
        return requireByDefault ? selectFlow(input, relyingPartyId, clientIp) : null;
    }

    private static String selectFlow(final ProfileRequestContext input, final String relyingPartyId,
            final String clientIp) {
        final String user = passwordUsername(input);
        if (user.isEmpty()) {
            LOG.warn("MFA method could not be resolved because password username was unavailable; using GraphicalMatrix");
            return EXTERNAL_FLOW;
        }

        final GraphicalMatrixMfaSettings settings;
        try {
            settings = GraphicalMatrixRuntime.repository().findMfaSettings(user);
        } catch (Exception ex) {
            LOG.warn("MFA method DB lookup failed for user={}, using GraphicalMatrix: {}", user, ex.toString());
            return EXTERNAL_FLOW;
        }

        final String method = settings != null ? settings.getMethod() : null;
        final String normalized = normalizeMethod(method);
        if ("TOTP".equals(normalized)) {
            if (!settings.isTotpActive()) {
                LOG.info("MFA method decision: user={}, sp={}, ip={}, method=TOTP, status={}, seedSet={}, flow={}",
                    user, relyingPartyId, clientIp, settings.getTotpStatus(),
                    settings.isTotpSeedSet(), EXTERNAL_FLOW);
                return EXTERNAL_FLOW;
            }
            LOG.info("MFA method decision: user={}, sp={}, ip={}, method=TOTP, flow={}",
                user, relyingPartyId, clientIp, TOTP_FLOW);
            return TOTP_FLOW;
        }

        if ("GRAPHICALMATRIX".equals(normalized)) {
            LOG.info("MFA method decision: user={}, sp={}, ip={}, method=GraphicalMatrix, flow={}",
                user, relyingPartyId, clientIp, EXTERNAL_FLOW);
            return EXTERNAL_FLOW;
        }

        if ("WEBAUTHN".equals(normalized)) {
            if (isWebAuthnRegistrationRequest()) {
                LOG.info("MFA method decision: user={}, sp={}, ip={}, method={}, registration=true, flow={}",
                    user, relyingPartyId, clientIp, normalized, EXTERNAL_FLOW);
                return EXTERNAL_FLOW;
            }
            LOG.info("MFA method decision: user={}, sp={}, ip={}, method={}, flow={}",
                user, relyingPartyId, clientIp, normalized, WEBAUTHN_FLOW);
            return WEBAUTHN_FLOW;
        }

        LOG.warn("MFA method is missing or unsupported for user={}, method='{}'; using GraphicalMatrix",
            user, trim(method));
        return EXTERNAL_FLOW;
    }

    private static String passwordUsername(final ProfileRequestContext input) {
        final AuthenticationContext authnCtx =
            input != null ? input.getSubcontext(AuthenticationContext.class) : null;
        final MultiFactorAuthenticationContext mfaCtx =
            authnCtx != null ? authnCtx.getSubcontext(MultiFactorAuthenticationContext.class) : null;
        final AuthenticationResult pwResult =
            mfaCtx != null ? mfaCtx.getActiveResults().get("authn/Password") : null;
        if (pwResult != null && pwResult.getSubject() != null) {
            final Set<UsernamePrincipal> usernames =
                pwResult.getSubject().getPrincipals(UsernamePrincipal.class);
            if (!usernames.isEmpty()) {
                return trim(usernames.iterator().next().getName());
            }
        }
        return "";
    }

    private static String normalizeMethod(final String method) {
        String value = trim(method);
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        return value.toUpperCase();
    }

    private static Properties loadPolicy() {
        final Properties policy = new Properties();
        final String idpHome = System.getProperty("idp.home", "/opt/shibboleth-idp");
        try (FileInputStream in = new FileInputStream(idpHome + "/conf/graphicalmatrix/mfa-policy.properties")) {
            policy.load(in);
        } catch (Exception ex) {
            LOG.warn("GraphicalMatrix MFA policy file could not be loaded, using default require policy: {}",
                ex.toString());
        }
        return policy;
    }

    private static String relyingPartyId(final ProfileRequestContext input) {
        if (input == null) {
            return "";
        }
        final RelyingPartyContext rpCtx = input.getSubcontext(RelyingPartyContext.class);
        return rpCtx != null && rpCtx.getRelyingPartyId() != null ? rpCtx.getRelyingPartyId() : "";
    }

    static boolean isSelfServiceProfile(final ProfileRequestContext input) {
        return input != null
            && GraphicalMatrixSelfServiceAuthentication.PROFILE_ID.equals(input.getProfileId());
    }

    private static String clientIp(final Properties policy) {
        final HttpServletRequest request = HttpServletRequestResponseContext.getRequest();
        if (request == null) {
            return "";
        }

        if (Boolean.parseBoolean(trim(policy.getProperty("graphicalmatrix.mfa.useForwardedFor", "false")))) {
            final String xff = firstHeaderIp(request.getHeader("X-Forwarded-For"));
            if (!xff.isEmpty()) {
                return xff;
            }
            final String realIp = trim(request.getHeader("X-Real-IP"));
            if (!realIp.isEmpty()) {
                return realIp;
            }
        }

        return trim(request.getRemoteAddr());
    }

    private static String firstHeaderIp(final String value) {
        final String header = trim(value);
        if (header.isEmpty()) {
            return "";
        }
        return trim(header.split(",")[0]);
    }

    private static boolean isWebAuthnRegistrationRequest() {
        final HttpServletRequest request = HttpServletRequestResponseContext.getRequest();
        final String uri = request != null ? trim(request.getRequestURI()) : "";
        return uri.contains("/profile/admin/webauthn-registration");
    }

    private static boolean ipMatches(final Properties policy, final String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        if (contains(csv(policy.getProperty("graphicalmatrix.mfa.bypassIPs")), ip)) {
            return true;
        }

        for (final String cidr : csv(policy.getProperty("graphicalmatrix.mfa.bypassCIDRs"))) {
            if (ipv4CidrMatches(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ipv4CidrMatches(final String ip, final String cidr) {
        try {
            final String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }
            final int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            final int ipValue = ipv4ToInt(ip);
            final int networkValue = ipv4ToInt(parts[0]);
            final int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            return (ipValue & mask) == (networkValue & mask);
        } catch (Exception ex) {
            return false;
        }
    }

    private static int ipv4ToInt(final String value) throws Exception {
        final byte[] bytes = InetAddress.getByName(value).getAddress();
        if (bytes.length != 4) {
            throw new IllegalArgumentException("not IPv4");
        }
        return ((bytes[0] & 0xff) << 24)
            | ((bytes[1] & 0xff) << 16)
            | ((bytes[2] & 0xff) << 8)
            | (bytes[3] & 0xff);
    }

    private static Set<String> csv(final String value) {
        final Set<String> out = new HashSet<>();
        if (value == null || value.trim().isEmpty()) {
            return out;
        }
        Arrays.stream(value.split(","))
            .map(GraphicalMatrixMfaDecisionStrategy::trim)
            .filter(s -> !s.isEmpty())
            .forEach(out::add);
        return out;
    }

    private static boolean contains(final Set<String> values, final String value) {
        return value != null && values.contains(value);
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
