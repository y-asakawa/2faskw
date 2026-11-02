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
import net.shibboleth.shared.servlet.impl.HttpServletRequestResponseContext;

/** Revalidates enrollment state and subject binding after the selected second factor succeeds. */
public final class GraphicalMatrixMfaCompletionStrategy implements Function<ProfileRequestContext, String> {
    private static final Logger LOG = LoggerFactory.getLogger(GraphicalMatrixMfaCompletionStrategy.class);

    public void initialize() {
    }

    public void destroy() {
    }

    @Override
    public String apply(final ProfileRequestContext input) {
        if (input != null) { input.removeSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class); }
        final MultiFactorAuthenticationContext mfaCtx = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        final GraphicalMatrixMfaGuardContext guard = mfaCtx != null
            ? mfaCtx.getSubcontext(GraphicalMatrixMfaGuardContext.class) : null;
        if (mfaCtx == null || guard == null) {
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                "MFA completion guard is missing");
        }

        final String passwordUser = GraphicalMatrixMfaSubjectSupport.passwordUsername(input);
        final AuthenticationResult secondResult = mfaCtx.getActiveResults().get(guard.expectedFlow());
        final String secondFactorUser = GraphicalMatrixMfaSubjectSupport.secondFactorUsername(
            input, secondResult, guard.expectedFlow());
        if (passwordUser.isEmpty() || secondFactorUser.isEmpty()
                || !guard.user().equals(passwordUser) || !guard.user().equals(secondFactorUser)) {
            LOG.warn("MFA factor subject binding details: flow={}, passwordIdentityPresent={}, factorResultPresent={}, factorIdentityPresent={}",
                guard.expectedFlow(), !passwordUser.isEmpty(), secondResult != null, !secondFactorUser.isEmpty());
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                "MFA factor subject binding failed");
        }

        final GraphicalMatrixMfaSuccessRecord.Expectation expected = GraphicalMatrixMfaSuccessRecord.Expectation.from(guard);
        GraphicalMatrixMfaCompletionRecordContext recording = input.getSubcontext(GraphicalMatrixMfaCompletionRecordContext.class);
        if (recording == null) {
            recording = new GraphicalMatrixMfaCompletionRecordContext();
            input.addSubcontext(recording, true);
        }
        final AuthenticationResult passwordResult = mfaCtx.getActiveResults().get("authn/Password");
        final GraphicalMatrixMfaSuccessRecordOptions options;
        final boolean firstAttempt = !recording.attempted;
        final GraphicalMatrixMfaSuccessRecord.Result completed;
        try {
            options = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome()).getMfaSuccessRecordOptions();
            if (firstAttempt) {
                recording.bind(expected, passwordResult, secondResult, input.getProfileId());
                completed = GraphicalMatrixRuntime.repository().checkAndRecordMfaCompletion(expected, options);
                recording.result = completed;
            } else {
                // A prior metadata result is never evidence that the enrollment is still valid.
                completed = expected.matches(GraphicalMatrixRuntime.repository().findMfaSettings(guard.user()))
                    ? new GraphicalMatrixMfaSuccessRecord.Result(GraphicalMatrixMfaSuccessRecord.StateVerdict.VALID,
                        GraphicalMatrixMfaSuccessRecord.StoreStatus.NOT_ATTEMPTED, 0L, null, false)
                    : GraphicalMatrixMfaSuccessRecord.Result.rejected();
            }
        } catch (Exception ex) {
            LOG.warn("MFA completion configuration/state lookup failed for user={} type={}",
                guard.user(), ex.getClass().getSimpleName());
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.SERVICE_UNAVAILABLE_EVENT,
                "MFA completion state lookup failed");
        }

        if (completed == null || !completed.valid()) {
            return deny(mfaCtx, GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                "MFA enrollment changed before completion");
        }

        if (GraphicalMatrixMfaDecisionStrategy.isSelfServiceProfile(input)) {
            input.addSubcontext(new GraphicalMatrixSelfServiceAuthorizationContext(
                guard.user(), guard.stateVersion(), guard.expectedFlow(),
                passwordResult, secondResult), true);
        }
        if (firstAttempt && completed.failureCode() != null) {
            LOG.warn("MFA completion metadata failed: user={}, eventId={}, code={}",
                guard.user(), recording.eventId, completed.failureCode());
        }
        if (firstAttempt && completed.futureTimestamp()) {
            LOG.warn("MFA completion stored timestamp is in the future: user={}, eventId={}", guard.user(), recording.eventId);
        }
        if (firstAttempt && options.auditEnabled() && !recording.auditAttempted
                && recording.matches(expected, passwordResult, secondResult, input.getProfileId())) {
            recording.auditAttempted = true;
            try {
                final String detail = GraphicalMatrixMfaCompletionAudit.detail(input, expected, completed, recording.eventId);
                if (!GraphicalMatrixRuntime.auditLogger().logCompletion(guard.user(), detail,
                        HttpServletRequestResponseContext.getRequest(), completed.completedAtMillis())) {
                    LOG.warn("MFA completion audit unavailable: eventId={}, alternative=true, method={}, completedAt={}, store={}",
                        recording.eventId, guard.expectedMethod(), completed.completedAtMillis(), completed.recordStatus());
                }
            } catch (Exception ex) {
                LOG.warn("MFA completion audit unavailable: eventId={}, alternative=true, method={}, completedAt={}, store={}, type={}",
                    recording.eventId, guard.expectedMethod(), completed.completedAtMillis(), completed.recordStatus(),
                    ex.getClass().getSimpleName());
            }
        }
        LOG.info("MFA completion accepted: user={}, flow={}, stateVersion={}",
            guard.user(), guard.expectedFlow(), guard.stateVersion());
        return null;
    }

    static boolean validCompletion(final GraphicalMatrixMfaSettings current,
            final GraphicalMatrixMfaGuardContext guard) {
        return GraphicalMatrixMfaSuccessRecord.Expectation.from(guard).matches(current);
    }

    static boolean advanceGraphicalMatrixGuard(final ProfileRequestContext input,
            final String user, final long previousStateVersion) {
        if (previousStateVersion == Long.MAX_VALUE) {
            return false;
        }
        if (input != null) { input.removeSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class); }
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

    static boolean acceptVerificationTransition(final ProfileRequestContext input, final String user,
            final GraphicalMatrixVerifyResult result) {
        final MultiFactorAuthenticationContext mfa = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        final GraphicalMatrixMfaGuardContext guard = mfa != null
            ? mfa.getSubcontext(GraphicalMatrixMfaGuardContext.class) : null;
        if (guard == null || !guard.user().equals(user) || !"authn/External".equals(guard.expectedFlow())
                || !"GRAPHICALMATRIX".equals(guard.expectedMethod())
                || result.beforeVersion() == null || result.afterVersion() == null
                || guard.stateVersion() != result.beforeVersion().longValue()) {
            return false;
        }
        mfa.addSubcontext(new GraphicalMatrixMfaGuardContext(user, guard.expectedFlow(),
            guard.expectedMethod(), result.afterVersion().longValue()), true);
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
