/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import net.shibboleth.idp.authn.AuthenticationFlowDescriptor;
import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.plugin.authn.webauthn.context.WebAuthnAuthenticationContext;
import net.shibboleth.idp.plugin.authn.webauthn.impl.ValidateWebAuthnAssertion;
import org.opensaml.profile.context.ProfileRequestContext;

/** Exercises the official result producer, not the browser or authenticator signature ceremony. */
final class OfficialWebAuthnTestSupport extends ValidateWebAuthnAssertion {
    static AuthenticationResult result(final ProfileRequestContext input, final String user,
            final boolean secondFactor) {
        final var authn = input.ensureSubcontext(AuthenticationContext.class);
        final var previous = authn.getAuthenticationResult();
        final var previousFlow = authn.getAttemptedFlow();
        final var flow = new AuthenticationFlowDescriptor();
        flow.setId("authn/WebAuthn");
        authn.setAttemptedFlow(flow);
        final var webauthn = new WebAuthnAuthenticationContext();
        webauthn.setUsername(user);
        webauthn.setUserId(("test-user-handle-" + user).getBytes(StandardCharsets.UTF_8));
        webauthn.setSecondFactor(secondFactor);
        webauthn.setPublicKeyCredentialRequestOptions(PublicKeyCredentialRequestOptions.builder()
            .challenge(new ByteArray(new byte[32])).build());
        authn.addSubcontext(webauthn, true);
        try {
            final var validator = new OfficialWebAuthnTestSupport();
            assertTrue(validator.doPreExecute(input, authn));
            validator.buildAuthenticationResult(input, authn);
            return authn.getAuthenticationResult();
        } finally {
            new ValidateWebAuthnAssertion.WebAuthnCleanupHook().accept(input);
            authn.setAuthenticationResult(previous);
            authn.setAttemptedFlow(previousFlow);
        }
    }
}
