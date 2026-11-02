/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.security.Principal;
import java.util.List;
import javax.security.auth.Subject;
import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.principal.TOTPPrincipal;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import org.junit.jupiter.api.Test;
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixMfaSubjectSupportTest {
    @Test void recognizesTotpIdentityWithoutChangingPasswordIdentityExtraction() {
        final var result = result("authn/TOTP", new TOTPPrincipal("alice"));
        assertEquals("alice", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result, "authn/TOTP"));
        assertEquals("", GraphicalMatrixMfaSubjectSupport.exactUsername(result));
    }

    @Test void acceptsConsistentDualPrincipalsAndCustomUsernameOnlyValidator() {
        for (final var result : List.of(
                result("authn/TOTP", new TOTPPrincipal("alice"), new UsernamePrincipal("alice")),
                result("authn/TOTP", new UsernamePrincipal("alice")))) {
            assertEquals("alice", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result, "authn/TOTP"));
        }
    }

    @Test void rejectsConflictingOrAmbiguousTotpIdentities() {
        for (final var result : List.of(
                result("authn/TOTP", new TOTPPrincipal("alice"), new UsernamePrincipal("bob")),
                result("authn/TOTP", new TOTPPrincipal("alice"), new TOTPPrincipal("bob")),
                result("authn/TOTP", new TOTPPrincipal("alice"), new UsernamePrincipal("alice"), new UsernamePrincipal("bob")),
                result("authn/TOTP"))) {
            assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result, "authn/TOTP"));
        }
    }

    @Test void requiresTheSelectedFlowAndDoesNotGeneralizeTotpIdentityToOtherFactors() {
        final var totp = result("authn/TOTP", new TOTPPrincipal("alice"));
        for (final String flow : List.of("authn/Password", "authn/External", "authn/WebAuthn", "authn/Unknown")) {
            assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(totp, flow));
            assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result(flow, new TOTPPrincipal("alice")), flow));
        }
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(null, "authn/TOTP"));
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(totp, null));
    }

    @Test void keepsUsernameUniquenessForGraphicalMatrixAndWebauthn() {
        for (final String flow : List.of("authn/External", "authn/WebAuthn")) {
            assertEquals("alice", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result(flow, new UsernamePrincipal("alice")), flow));
            assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result(flow,
                new UsernamePrincipal("alice"), new UsernamePrincipal("bob")), flow));
            assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(result("authn/TOTP", new UsernamePrincipal("alice")), flow));
        }
    }

    @Test void cannotUseTotpPrincipalAsPasswordProof() {
        final var input = new ProfileRequestContext();
        final var authn = new AuthenticationContext();
        final var mfa = new MultiFactorAuthenticationContext();
        mfa.getActiveResults().put("authn/Password", result("authn/Password", new TOTPPrincipal("alice")));
        authn.addSubcontext(mfa);
        input.addSubcontext(authn);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.passwordUsername(input));
    }

    static AuthenticationResult result(final String flow, final Principal... principals) {
        final var subject = new Subject();
        subject.getPrincipals().addAll(List.of(principals));
        return new AuthenticationResult(flow, subject);
    }
}
