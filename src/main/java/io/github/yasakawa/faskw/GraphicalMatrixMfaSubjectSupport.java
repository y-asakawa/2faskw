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
