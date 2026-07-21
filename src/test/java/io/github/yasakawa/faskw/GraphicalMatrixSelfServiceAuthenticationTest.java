package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import javax.security.auth.Subject;

import org.junit.jupiter.api.Test;
import org.opensaml.profile.context.ProfileRequestContext;

import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.context.SubjectContext;
import net.shibboleth.idp.authn.principal.AuthenticationResultPrincipal;

final class GraphicalMatrixSelfServiceAuthenticationTest {
    @Test
    void acceptsFreshPasswordAndCurrentSecondFactor() {
        final ProfileRequestContext prc = context("test01", "authn/TOTP");

        final GraphicalMatrixSelfServiceAuthentication.ValidationResult result =
            GraphicalMatrixSelfServiceAuthentication.validate(prc);

        assertTrue(result.isValid());
        assertEquals("test01", result.getUser());
        assertEquals("authn/TOTP", result.getSecondFactor());
    }

    @Test
    void rejectsPasswordOnlyAuthentication() {
        final ProfileRequestContext prc = context("test01", null);

        final GraphicalMatrixSelfServiceAuthentication.ValidationResult result =
            GraphicalMatrixSelfServiceAuthentication.validate(prc);

        assertFalse(result.isValid());
        assertEquals("second_factor_result_required", result.getFailureReason());
    }

    @Test
    void rejectsPreviousSecondFactorResult() {
        final ProfileRequestContext prc = context("test01", "authn/WebAuthn");
        final AuthenticationContext authn = prc.getSubcontext(AuthenticationContext.class);
        authn.getSubcontext(MultiFactorAuthenticationContext.class)
            .getActiveResults().get("authn/WebAuthn").setPreviousResult(true);

        assertFalse(GraphicalMatrixSelfServiceAuthentication.validate(prc).isValid());
    }

    @Test
    void acceptsMergedComponentResultsAfterMfaSubflowContextIsRemoved() {
        final ProfileRequestContext prc = contextWithMergedResults("test01", "authn/External");

        final GraphicalMatrixSelfServiceAuthentication.ValidationResult result =
            GraphicalMatrixSelfServiceAuthentication.validate(prc);

        assertTrue(result.isValid());
        assertEquals("test01", result.getUser());
        assertEquals("authn/External", result.getSecondFactor());
    }

    @Test
    void rejectsMergedPasswordOnlyAuthentication() {
        final ProfileRequestContext prc = contextWithMergedResults("test01", null);

        final GraphicalMatrixSelfServiceAuthentication.ValidationResult result =
            GraphicalMatrixSelfServiceAuthentication.validate(prc);

        assertFalse(result.isValid());
        assertEquals("second_factor_result_required", result.getFailureReason());
    }

    @Test
    void rejectsNonForcedAuthentication() {
        final ProfileRequestContext prc = context("test01", "authn/External");
        prc.getSubcontext(AuthenticationContext.class).setForceAuthn(false);

        assertFalse(GraphicalMatrixSelfServiceAuthentication.validate(prc).isValid());
    }

    @Test
    void rejectsUnexpectedProfileAndInvalidSubject() {
        final ProfileRequestContext wrongProfile = context("test01", "authn/TOTP");
        wrongProfile.setProfileId("https://sp.example.test/profile");
        assertFalse(GraphicalMatrixSelfServiceAuthentication.validate(wrongProfile).isValid());

        assertFalse(GraphicalMatrixSelfServiceAuthentication.validate(
            context("../other-user", "authn/TOTP")).isValid());
    }

    private static ProfileRequestContext context(final String user, final String secondFactor) {
        final ProfileRequestContext prc = new ProfileRequestContext();
        prc.setProfileId(GraphicalMatrixSelfServiceAuthentication.PROFILE_ID);

        final SubjectContext subject = new SubjectContext();
        subject.setPrincipalName(user);
        prc.addSubcontext(subject);

        final AuthenticationContext authn = new AuthenticationContext();
        authn.setForceAuthn(true);
        final AuthenticationResult mfaResult = result("authn/MFA");
        authn.setAuthenticationResult(mfaResult);

        final MultiFactorAuthenticationContext mfa = new MultiFactorAuthenticationContext();
        mfa.getActiveResults().put("authn/Password", result("authn/Password"));
        if (secondFactor != null) {
            mfa.getActiveResults().put(secondFactor, result(secondFactor));
        }
        authn.addSubcontext(mfa);
        prc.addSubcontext(authn);
        return prc;
    }

    private static ProfileRequestContext contextWithMergedResults(final String user, final String secondFactor) {
        final ProfileRequestContext prc = new ProfileRequestContext();
        prc.setProfileId(GraphicalMatrixSelfServiceAuthentication.PROFILE_ID);

        final SubjectContext subject = new SubjectContext();
        subject.setPrincipalName(user);
        prc.addSubcontext(subject);

        final Subject mergedSubject = new Subject();
        mergedSubject.getPrincipals().add(new AuthenticationResultPrincipal(result("authn/Password")));
        if (secondFactor != null) {
            mergedSubject.getPrincipals().add(new AuthenticationResultPrincipal(result(secondFactor)));
        }

        final AuthenticationResult mfaResult = new AuthenticationResult("authn/MFA", mergedSubject);
        mfaResult.setAuthenticationInstant(Instant.now());
        final AuthenticationContext authn = new AuthenticationContext();
        authn.setForceAuthn(true);
        authn.setAuthenticationResult(mfaResult);
        prc.addSubcontext(authn);
        return prc;
    }

    private static AuthenticationResult result(final String flowId) {
        final AuthenticationResult result = new AuthenticationResult(flowId, new Subject());
        result.setAuthenticationInstant(Instant.now());
        return result;
    }
}
