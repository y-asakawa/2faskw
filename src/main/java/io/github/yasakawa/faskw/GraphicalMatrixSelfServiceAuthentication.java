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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.context.SubjectContext;
import net.shibboleth.idp.authn.principal.AuthenticationResultPrincipal;
import org.opensaml.profile.context.ProfileRequestContext;

/** Validates the fresh Password plus MFA result used by the self-service flow. */
public final class GraphicalMatrixSelfServiceAuthentication {
    public static final String PROFILE_ID =
        "https://github.com/y-asakawa/2faskw/profile/self-service";
    public static final String PROFILE_PATH = "/profile/2faskw/self-service";

    private static final String MFA_FLOW = "authn/MFA";
    private static final String PASSWORD_FLOW = "authn/Password";
    private static final Set<String> SECOND_FACTOR_FLOWS =
        Set.of("authn/External", "authn/TOTP", "authn/WebAuthn");
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]+");

    private GraphicalMatrixSelfServiceAuthentication() {
    }

    public static ValidationResult validate(final ProfileRequestContext profileRequestContext) {
        if (profileRequestContext == null || !PROFILE_ID.equals(profileRequestContext.getProfileId())) {
            return ValidationResult.failed("unexpected_profile");
        }

        final SubjectContext subjectContext =
            profileRequestContext.getSubcontext(SubjectContext.class);
        final String user = subjectContext != null ? trim(subjectContext.getPrincipalName()) : "";
        if (user.isEmpty() || !USER_ID.matcher(user).matches()) {
            return ValidationResult.failed("invalid_subject");
        }

        final AuthenticationContext authenticationContext =
            profileRequestContext.getSubcontext(AuthenticationContext.class);
        if (authenticationContext == null || !authenticationContext.isForceAuthn()) {
            return ValidationResult.failed("fresh_authentication_required");
        }

        final AuthenticationResult authenticationResult = authenticationContext.getAuthenticationResult();
        if (authenticationResult == null
                || !MFA_FLOW.equals(authenticationResult.getAuthenticationFlowId())
                || authenticationResult.isPreviousResult()) {
            return ValidationResult.failed("current_mfa_result_required");
        }

        final Map<String, AuthenticationResult> componentResults = componentResults(
            authenticationResult, authenticationContext.getSubcontext(MultiFactorAuthenticationContext.class));
        if (!isCurrent(componentResults.get(PASSWORD_FLOW))) {
            return ValidationResult.failed("password_result_required");
        }

        String secondFactor = null;
        Instant completedAt = authenticationResult.getAuthenticationInstant();
        for (final String flowId : SECOND_FACTOR_FLOWS) {
            final AuthenticationResult factorResult = componentResults.get(flowId);
            if (isCurrent(factorResult)) {
                secondFactor = flowId;
                if (completedAt == null || factorResult.getAuthenticationInstant().isAfter(completedAt)) {
                    completedAt = factorResult.getAuthenticationInstant();
                }
                break;
            }
        }
        if (secondFactor == null) {
            return ValidationResult.failed("second_factor_result_required");
        }

        if (completedAt == null) {
            completedAt = authenticationContext.getCompletionInstant();
        }
        return ValidationResult.success(user, secondFactor, completedAt);
    }

    private static Map<String, AuthenticationResult> componentResults(
            final AuthenticationResult authenticationResult,
            final MultiFactorAuthenticationContext mfaContext) {
        final Map<String, AuthenticationResult> results = new HashMap<>();
        if (authenticationResult.getSubject() != null) {
            for (final AuthenticationResultPrincipal principal : authenticationResult.getSubject()
                    .getPrincipals(AuthenticationResultPrincipal.class)) {
                final AuthenticationResult result = principal.getAuthenticationResult();
                if (result != null && result.getAuthenticationFlowId() != null) {
                    results.put(result.getAuthenticationFlowId(), result);
                }
            }
        }
        if (mfaContext != null) {
            mfaContext.getActiveResults().forEach(results::putIfAbsent);
        }
        return results;
    }

    private static boolean isCurrent(final AuthenticationResult result) {
        return result != null && !result.isPreviousResult() && result.getAuthenticationInstant() != null;
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final String user;
        private final String secondFactor;
        private final Instant completedAt;
        private final String failureReason;

        private ValidationResult(final boolean valid, final String user, final String secondFactor,
                final Instant completedAt, final String failureReason) {
            this.valid = valid;
            this.user = user;
            this.secondFactor = secondFactor;
            this.completedAt = completedAt;
            this.failureReason = failureReason;
        }

        private static ValidationResult success(final String user, final String secondFactor,
                final Instant completedAt) {
            return new ValidationResult(true, user, secondFactor, completedAt, null);
        }

        private static ValidationResult failed(final String reason) {
            return new ValidationResult(false, null, null, null, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getUser() {
            return user;
        }

        public String getSecondFactor() {
            return secondFactor;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }
}
