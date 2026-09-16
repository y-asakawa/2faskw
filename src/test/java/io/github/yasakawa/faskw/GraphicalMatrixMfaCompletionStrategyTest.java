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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.security.auth.Subject;

import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixMfaCompletionStrategyTest {
    private static final GraphicalMatrixMfaGuardContext GRAPHICAL_GUARD =
        new GraphicalMatrixMfaGuardContext("alice", "authn/External", "GRAPHICALMATRIX", 7L);

    @Test
    void acceptsOnlyUnchangedActiveGraphicalMatrixEnrollment() {
        assertTrue(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "GraphicalMatrix", "UNREGISTERED", false, true, 7L),
            GRAPHICAL_GUARD));

        assertFalse(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("DISABLED", "GraphicalMatrix", "UNREGISTERED", false, true, 8L),
            GRAPHICAL_GUARD));
        assertFalse(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "GraphicalMatrix", "UNREGISTERED", false, true, 8L),
            GRAPHICAL_GUARD));
        assertFalse(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "TOTP", "ACTIVE", true, true, 7L),
            GRAPHICAL_GUARD));
        assertFalse(GraphicalMatrixMfaCompletionStrategy.validCompletion(null, GRAPHICAL_GUARD));
    }

    @Test
    void requiresMethodSpecificCredentialReadiness() {
        final GraphicalMatrixMfaGuardContext totp =
            new GraphicalMatrixMfaGuardContext("alice", "authn/TOTP", "TOTP", 4L);
        assertTrue(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "TOTP", "ACTIVE", true, true, 4L), totp));
        assertFalse(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "TOTP", "PENDING", true, true, 4L), totp));
        assertFalse(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "TOTP", "ACTIVE", false, true, 4L), totp));

        final GraphicalMatrixMfaGuardContext webauthn =
            new GraphicalMatrixMfaGuardContext("alice", "authn/WebAuthn", "WEBAUTHN", 2L);
        assertTrue(GraphicalMatrixMfaCompletionStrategy.validCompletion(
            settings("ACTIVE", "WebAuthn", "UNREGISTERED", false, true, 2L), webauthn));
    }

    @Test
    void acceptsOnlyOneNonEmptyUsernamePrincipal() {
        assertEquals("alice", GraphicalMatrixMfaSubjectSupport.exactUsername(result("alice")));
        assertTrue(GraphicalMatrixMfaSubjectSupport.exactUsername(result()).isEmpty());
        assertTrue(GraphicalMatrixMfaSubjectSupport.exactUsername(result("alice", "bob")).isEmpty());
    }

    private static AuthenticationResult result(final String... users) {
        final Subject subject = new Subject();
        for (final String user : users) {
            subject.getPrincipals().add(new UsernamePrincipal(user));
        }
        return new AuthenticationResult("authn/Test", subject);
    }

    private static GraphicalMatrixMfaSettings settings(final String status, final String method,
            final String totpStatus, final boolean seed, final boolean sequence, final long version) {
        return new GraphicalMatrixMfaSettings(status, method, totpStatus, seed, sequence, version);
    }
}
