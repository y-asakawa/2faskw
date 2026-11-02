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

import java.util.Set;

import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.context.SubjectCanonicalizationContext;
import net.shibboleth.idp.authn.principal.TOTPPrincipal;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixMfaSubjectSupport {
    static final String PASSWORD_FLOW = "authn/Password";

    private GraphicalMatrixMfaSubjectSupport() {
    }

    static MultiFactorAuthenticationContext mfaContext(final ProfileRequestContext input) {
        final AuthenticationContext authnCtx = input != null
            ? input.getSubcontext(AuthenticationContext.class) : null;
        return authnCtx != null
            ? authnCtx.getSubcontext(MultiFactorAuthenticationContext.class) : null;
    }

    static String passwordUsername(final ProfileRequestContext input) {
        final MultiFactorAuthenticationContext mfaCtx = mfaContext(input);
        return exactUsername(mfaCtx != null
            ? mfaCtx.getActiveResults().get(PASSWORD_FLOW) : null);
    }

    static String secondFactorUsername(final ProfileRequestContext input,
            final AuthenticationResult result, final String expectedFlow) {
        if (result == null || result.getSubject() == null || expectedFlow == null
                || !expectedFlow.equals(result.getAuthenticationFlowId())) {
            return "";
        }
        return switch (expectedFlow) {
            case "authn/External" -> exactUsername(result);
            case "authn/WebAuthn" -> webauthnUsername(input, result);
            case "authn/TOTP" -> totpUsername(result);
            default -> "";
        };
    }

    static String secondFactorUsername(final AuthenticationResult result, final String expectedFlow) {
        return secondFactorUsername(null, result, expectedFlow);
    }

    private static String webauthnUsername(final ProfileRequestContext input, final AuthenticationResult result) {
        final Set<UsernamePrincipal> usernames = result.getSubject().getPrincipals(UsernamePrincipal.class);
        if (usernames.size() > 1) {
            return "";
        }
        final SubjectCanonicalizationContext canonical = input != null
            ? input.getSubcontext(SubjectCanonicalizationContext.class) : null;
        // Official 2FA results can omit UsernamePrincipal; only their own c14n output can supply it.
        if (canonical != null && canonical.getSubject() == result.getSubject()) {
            if (result.isPreviousResult() || canonical.getException() != null) {
                return "";
            }
            final String name = canonical.getPrincipalName();
            final String user = name != null ? name.trim() : "";
            return usernames.isEmpty() || user.equals(exactUsername(result)) ? user : "";
        }
        return exactUsername(result);
    }

    private static String totpUsername(final AuthenticationResult result) {
        // The official TOTP validator emits TOTPPrincipal, not UsernamePrincipal.
        final Set<TOTPPrincipal> totp = result.getSubject().getPrincipals(TOTPPrincipal.class);
        final Set<UsernamePrincipal> usernames = result.getSubject().getPrincipals(UsernamePrincipal.class);
        if (totp.size() > 1 || usernames.size() > 1) {
            return "";
        }
        if (totp.isEmpty()) {
            return exactUsername(result); // Retain custom validators that emit only UsernamePrincipal.
        }
        final String name = totp.iterator().next().getName();
        final String user = name != null ? name.trim() : "";
        return usernames.isEmpty() || user.equals(exactUsername(result)) ? user : "";
    }

    static String exactUsername(final AuthenticationResult result) {
        if (result == null || result.getSubject() == null) {
            return "";
        }
        final Set<UsernamePrincipal> principals =
            result.getSubject().getPrincipals(UsernamePrincipal.class);
        if (principals.size() != 1) {
            return "";
        }
        final String user = principals.iterator().next().getName();
        return user != null ? user.trim() : "";
    }
}
