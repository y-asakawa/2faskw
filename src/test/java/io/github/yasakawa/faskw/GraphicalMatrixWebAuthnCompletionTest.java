/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;

import javax.security.auth.Subject;
import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.SubjectCanonicalizationContext;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import net.shibboleth.idp.plugin.authn.webauthn.principal.WebAuthnUserIdPrinicpal;
import org.junit.jupiter.api.Test;
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixWebAuthnCompletionTest {
    @Test void officialSecondFactorResultHasBoundCanonicalNameWithoutUsernamePrincipal() {
        final var input = new ProfileRequestContext();
        final var result = OfficialWebAuthnTestSupport.result(input, "alice", true);
        assertTrue(result.getSubject().getPrincipals(UsernamePrincipal.class).isEmpty());
        assertEquals(1, result.getSubject().getPrincipals(WebAuthnUserIdPrinicpal.class).size());
        final var canonical = input.getSubcontext(SubjectCanonicalizationContext.class);
        assertSame(result.getSubject(), canonical.getSubject());
        assertEquals("alice", canonical.getPrincipalName());
        assertEquals("alice", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
    }

    @Test void officialPasswordlessResultKeepsUsernamePrincipalSupport() {
        final var input = new ProfileRequestContext();
        final var result = OfficialWebAuthnTestSupport.result(input, "alice", false);
        assertEquals(1, result.getSubject().getPrincipals(UsernamePrincipal.class).size());
        assertEquals("alice", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
    }

    @Test void missingUnboundAndEqualButDifferentSubjectsCannotSupplyIdentity() {
        final var input = new ProfileRequestContext();
        final var result = OfficialWebAuthnTestSupport.result(input, "alice", true);
        final var canonical = input.getSubcontext(SubjectCanonicalizationContext.class);
        final var lookalike = new Subject();
        lookalike.getPrincipals().addAll(result.getSubject().getPrincipals());
        assertEquals(result.getSubject(), lookalike);
        canonical.setSubject(lookalike);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
        canonical.setSubject(null);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
        input.removeSubcontext(SubjectCanonicalizationContext.class);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
    }

    @Test void ambiguousConflictingAndFailedCanonicalIdentitiesAreRejected() {
        final var input = new ProfileRequestContext();
        final var result = OfficialWebAuthnTestSupport.result(input, "alice", true);
        final var canonical = input.getSubcontext(SubjectCanonicalizationContext.class);
        canonical.setPrincipalName(null);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
        canonical.setPrincipalName("alice");
        canonical.setException(new IllegalArgumentException("test canonicalization failure"));
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
        canonical.setException(null);
        result.getSubject().getPrincipals().add(new UsernamePrincipal("bob"));
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
        result.getSubject().getPrincipals().add(new UsernamePrincipal("alice"));
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
    }

    @Test void canonicalIdentityIsNeverGeneralizedToOtherFlowsOrPreviousResults() {
        final var input = new ProfileRequestContext();
        final var result = OfficialWebAuthnTestSupport.result(input, "alice", true);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/TOTP"));
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/External"));
        for (final String flow : java.util.List.of("authn/TOTP", "authn/External")) {
            assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input,
                new AuthenticationResult(flow, result.getSubject()), flow));
        }
        result.setPreviousResult(true);
        assertEquals("", GraphicalMatrixMfaSubjectSupport.secondFactorUsername(input, result, "authn/WebAuthn"));
    }
}
