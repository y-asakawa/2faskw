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

import java.io.FileInputStream;
import java.util.Properties;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.profile.context.RelyingPartyContext;
import net.shibboleth.shared.servlet.impl.HttpServletRequestResponseContext;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GraphicalMatrixMfaDecisionStrategy implements Function<ProfileRequestContext, String> {
    private static final Logger LOG = LoggerFactory.getLogger(GraphicalMatrixMfaDecisionStrategy.class);
    public static final String ACCESS_DENIED_EVENT = "GraphicalMatrixAccessDenied";
    public static final String SERVICE_UNAVAILABLE_EVENT = "GraphicalMatrixServiceUnavailable";
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

        final GraphicalMatrixMfaPolicy mfaPolicy;
        try {
            mfaPolicy = GraphicalMatrixMfaPolicy.parse(policy);
        } catch (IllegalArgumentException ex) {
            LOG.error("MFA policy is invalid; denying authentication: sp={}, ip={}, error={}",
                relyingPartyId, clientIp, ex.getMessage());
            return deny(input, SERVICE_UNAVAILABLE_EVENT);
        }

        final GraphicalMatrixMfaPolicy.Decision decision;
        if (isSelfServiceProfile(input)) {
            LOG.info("MFA required for 2FAS-KW self-service profile: ip={}", clientIp);
            decision = new GraphicalMatrixMfaPolicy.Decision(
                GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "selfService");
        } else {
            decision = mfaPolicy.evaluate(relyingPartyId, clientIp);
        }
        LOG.info("MFA policy decision: rule={}, result={}, sp={}, ip={}",
            decision.rule(), decision.outcome().name().toLowerCase(), relyingPartyId, clientIp);
        return selectFlow(input, relyingPartyId, clientIp, decision, mfaPolicy);
    }

    private static String selectFlow(final ProfileRequestContext input, final String relyingPartyId,
            final String clientIp, final GraphicalMatrixMfaPolicy.Decision decision,
            final GraphicalMatrixMfaPolicy policy) {
        final String user = GraphicalMatrixMfaSubjectSupport.passwordUsername(input);
        if (user.isEmpty()) {
            LOG.warn("MFA enrollment could not be resolved because the password subject was not unique");
            return deny(input, ACCESS_DENIED_EVENT);
        }

        final GraphicalMatrixMfaSettings settings;
        try {
            settings = GraphicalMatrixRuntime.repository().findMfaSettings(user);
        } catch (Exception ex) {
            LOG.warn("MFA enrollment lookup failed for user={}: {}", user, ex.toString());
            return deny(input, SERVICE_UNAVAILABLE_EVENT);
        }

        if (settings == null) {
            if (decision.outcome() == GraphicalMatrixMfaPolicy.Outcome.BYPASS
                    && policy.missingEnrollmentPolicy()
                        == GraphicalMatrixMfaPolicy.MissingEnrollmentPolicy.ALLOW_ON_BYPASS) {
                LOG.warn("MFA bypass compatibility allowed a missing enrollment: user={}, sp={}, ip={}, rule={}",
                    user, relyingPartyId, clientIp, decision.rule());
                return null;
            }
            LOG.warn("MFA access denied because enrollment is missing: user={}, sp={}, ip={}, rule={}",
                user, relyingPartyId, clientIp, decision.rule());
            return deny(input, ACCESS_DENIED_EVENT);
        }
        if (!settings.hasValidStatus() || !settings.isActive()) {
            LOG.warn("MFA access denied because enrollment is not active: user={}, sp={}, ip={}, status={}",
                user, relyingPartyId, clientIp, settings.getStatus());
            return deny(input, ACCESS_DENIED_EVENT);
        }

        final String method = settings.getMethod();
        final String normalized = GraphicalMatrixMfaCompletionStrategy.normalizeMethod(method);
        if (!"TOTP".equals(normalized) && !"GRAPHICALMATRIX".equals(normalized)
                && !"WEBAUTHN".equals(normalized)) {
            LOG.warn("MFA access denied because method is missing or unsupported: user={}, method='{}'",
                user, trim(method));
            return deny(input, ACCESS_DENIED_EVENT);
        }
        if (decision.outcome() == GraphicalMatrixMfaPolicy.Outcome.BYPASS) {
            LOG.info("MFA bypass accepted after active enrollment check: user={}, sp={}, ip={}, rule={}",
                user, relyingPartyId, clientIp, decision.rule());
            return null;
        }

        final String flow;
        if ("TOTP".equals(normalized)) {
            if (!settings.isTotpActive()) {
                LOG.warn("MFA access denied because TOTP is not active: user={}, sp={}, ip={}, status={}, seedSet={}",
                    user, relyingPartyId, clientIp, settings.getTotpStatus(),
                    settings.isTotpSeedSet());
                return deny(input, ACCESS_DENIED_EVENT);
            }
            flow = TOTP_FLOW;
        } else if ("GRAPHICALMATRIX".equals(normalized)) {
            if (!settings.isSequenceSet()) {
                LOG.warn("MFA access denied because GraphicalMatrix sequence is missing: user={}, sp={}, ip={}",
                    user, relyingPartyId, clientIp);
                return deny(input, ACCESS_DENIED_EVENT);
            }
            flow = EXTERNAL_FLOW;
        } else {
            flow = WEBAUTHN_FLOW;
        }

        final MultiFactorAuthenticationContext mfaCtx = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        if (mfaCtx == null) {
            return deny(input, SERVICE_UNAVAILABLE_EVENT);
        }
        mfaCtx.addSubcontext(new GraphicalMatrixMfaGuardContext(
            user, flow, normalized, settings.getStateVersion()), true);
        LOG.info("MFA method decision: user={}, sp={}, ip={}, method={}, flow={}, stateVersion={}",
            user, relyingPartyId, clientIp, normalized, flow, settings.getStateVersion());
        return flow;
    }

    private static String deny(final ProfileRequestContext input, final String event) {
        final MultiFactorAuthenticationContext mfaCtx = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        if (mfaCtx != null) {
            mfaCtx.setEvent(event);
        }
        return null;
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

    static boolean spCidrMatches(final String rules, final String relyingPartyId, final String ip) {
        return GraphicalMatrixMfaPolicy.spCidrMatches(rules, relyingPartyId, ip);
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
