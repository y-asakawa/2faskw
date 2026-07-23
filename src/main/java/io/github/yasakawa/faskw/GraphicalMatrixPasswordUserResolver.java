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

import jakarta.servlet.http.HttpServletRequest;
import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.ExternalAuthentication;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import org.opensaml.profile.context.ProfileRequestContext;

public final class GraphicalMatrixPasswordUserResolver {
    private GraphicalMatrixPasswordUserResolver() {
    }

    public static String resolve(final String key, final HttpServletRequest request) throws Exception {
        final ProfileRequestContext prc = ExternalAuthentication.getProfileRequestContext(key, request);
        final AuthenticationContext authnCtx =
            (prc != null) ? prc.getSubcontext(AuthenticationContext.class) : null;
        final MultiFactorAuthenticationContext mfaCtx =
            (authnCtx != null) ? authnCtx.getSubcontext(MultiFactorAuthenticationContext.class) : null;
        final AuthenticationResult pwResult =
            (mfaCtx != null) ? mfaCtx.getActiveResults().get("authn/Password") : null;
        if (pwResult != null && pwResult.getSubject() != null) {
            final Set<UsernamePrincipal> ups =
                pwResult.getSubject().getPrincipals(UsernamePrincipal.class);
            if (!ups.isEmpty()) {
                return ups.iterator().next().getName();
            }
        }
        return null;
    }
}
