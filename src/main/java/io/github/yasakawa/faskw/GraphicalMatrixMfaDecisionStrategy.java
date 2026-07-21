package io.github.yasakawa.faskw;

import java.io.FileInputStream;
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

        final GraphicalMatrixMfaPolicy mfaPolicy;
        try {
            mfaPolicy = GraphicalMatrixMfaPolicy.parse(policy);
        } catch (IllegalArgumentException ex) {
            LOG.error("MFA policy is invalid; requiring MFA: sp={}, ip={}, error={}",
                relyingPartyId, clientIp, ex.getMessage());
            return selectFlow(input, relyingPartyId, clientIp);
        }

        final GraphicalMatrixMfaPolicy.Decision decision = mfaPolicy.evaluate(relyingPartyId, clientIp);
        LOG.info("MFA policy decision: rule={}, result={}, sp={}, ip={}",
            decision.rule(), decision.outcome().name().toLowerCase(), relyingPartyId, clientIp);
        return decision.outcome() == GraphicalMatrixMfaPolicy.Outcome.REQUIRE
            ? selectFlow(input, relyingPartyId, clientIp) : null;
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

    static boolean spCidrMatches(final String rules, final String relyingPartyId, final String ip) {
        return GraphicalMatrixMfaPolicy.spCidrMatches(rules, relyingPartyId, ip);
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
