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
