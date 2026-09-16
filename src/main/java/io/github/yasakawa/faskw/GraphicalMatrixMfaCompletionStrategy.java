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

import java.util.Locale;
import java.util.function.Function;

import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Revalidates enrollment state and subject binding after the selected second factor succeeds. */
public final class GraphicalMatrixMfaCompletionStrategy implements Function<ProfileRequestContext, String> {
    private static final Logger LOG = LoggerFactory.getLogger(GraphicalMatrixMfaCompletionStrategy.class);

    public void initialize() {
    }

    public void destroy() {
    }

    @Override
    public String apply(final ProfileRequestContext input) {
        final MultiFactorAuthenticationContext mfaCtx = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        final GraphicalMatrixMfaGuardContext guard = mfaCtx != null
            ? mfaCtx.getSubcontext(GraphicalMatrixMfaGuardContext.class) : null;
        if (mfaCtx == null || guard == null) {
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                "MFA completion guard is missing");
        }

        final String passwordUser = GraphicalMatrixMfaSubjectSupport.passwordUsername(input);
        final AuthenticationResult secondResult = mfaCtx.getActiveResults().get(guard.expectedFlow());
        final String secondFactorUser = GraphicalMatrixMfaSubjectSupport.exactUsername(secondResult);
        if (passwordUser.isEmpty() || secondFactorUser.isEmpty()
                || !guard.user().equals(passwordUser) || !guard.user().equals(secondFactorUser)) {
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                "MFA factor subject binding failed");
        }

        final GraphicalMatrixMfaSettings current;
        try {
            current = GraphicalMatrixRuntime.repository().findMfaSettings(guard.user());
        } catch (Exception ex) {
            LOG.warn("MFA completion state lookup failed for user={}: {}",
                guard.user(), ex.toString());
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.SERVICE_UNAVAILABLE_EVENT,
                "MFA completion state lookup failed");
        }

        if (!validCompletion(current, guard)) {
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                "MFA enrollment changed before completion");
        }

        LOG.info("MFA completion accepted: user={}, flow={}, stateVersion={}",
            guard.user(), guard.expectedFlow(), guard.stateVersion());
        return null;
    }

    static boolean validCompletion(final GraphicalMatrixMfaSettings current,
            final GraphicalMatrixMfaGuardContext guard) {
        if (current == null || !current.isActive() || !current.hasValidStatus()
                || current.getStateVersion() != guard.stateVersion()) {
            return false;
        }
        final String method = normalizeMethod(current.getMethod());
        if (!guard.expectedMethod().equals(method)) {
            return false;
        }
        if ("TOTP".equals(method)) {
            return current.isTotpActive();
        }
        if ("GRAPHICALMATRIX".equals(method)) {
            return current.isSequenceSet();
        }
        return "WEBAUTHN".equals(method);
    }

    static boolean advanceGraphicalMatrixGuard(final ProfileRequestContext input,
            final String user, final long previousStateVersion) {
        if (previousStateVersion == Long.MAX_VALUE) {
            return false;
        }
        final MultiFactorAuthenticationContext mfaCtx = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        final GraphicalMatrixMfaGuardContext guard = mfaCtx != null
            ? mfaCtx.getSubcontext(GraphicalMatrixMfaGuardContext.class) : null;
        if (guard == null
                || !guard.user().equals(user)
                || !"authn/External".equals(guard.expectedFlow())
                || !"GRAPHICALMATRIX".equals(guard.expectedMethod())
                || guard.stateVersion() != previousStateVersion) {
            return false;
        }
        mfaCtx.addSubcontext(new GraphicalMatrixMfaGuardContext(
            guard.user(), guard.expectedFlow(), guard.expectedMethod(), previousStateVersion + 1L), true);
        return true;
    }

    private static String deny(final MultiFactorAuthenticationContext mfaCtx,
            final String event, final String message) {
        LOG.warn(message);
        if (mfaCtx != null) {
            mfaCtx.setEvent(event);
        }
        return null;
    }

    static String normalizeMethod(final String method) {
        String value = method != null ? method.trim() : "";
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
