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

import java.util.function.BiConsumer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import net.shibboleth.idp.authn.context.SubjectContext;
import net.shibboleth.shared.servlet.impl.HttpServletRequestResponseContext;
import org.opensaml.profile.context.ProfileRequestContext;

/** Activates WebAuthn only after the official WebAuthn Plugin has stored a credential. */
public final class GraphicalMatrixWebAuthnRegistrationHook
        implements BiConsumer<ProfileRequestContext, Object> {

    @Override
    public void accept(final ProfileRequestContext profileRequestContext, final Object event) {
        final HttpServletRequest request = HttpServletRequestResponseContext.getRequest();
        if (request == null) {
            return;
        }
        final HttpSession session = request.getSession(false);
        final GraphicalMatrixWebAuthnRegistrationSession.PendingRegistration pending =
            GraphicalMatrixWebAuthnRegistrationSession.consume(session, System.currentTimeMillis());
        if (pending == null) {
            return;
        }

        final SubjectContext subjectContext = profileRequestContext != null
            ? profileRequestContext.getSubcontext(SubjectContext.class) : null;
        final String authenticatedUser = subjectContext != null
            ? trim(subjectContext.getPrincipalName()) : "";
        if (!pending.user().equals(authenticatedUser)) {
            audit(request, pending.user(), "DENIED", "authenticated_subject_mismatch");
            return;
        }

        try {
            final boolean activated = GraphicalMatrixRuntime.repository()
                .activateWebAuthnIfMethodCurrent(
                    pending.user(), pending.expectedMethod(), System.currentTimeMillis());
            audit(request, pending.user(), activated ? "OK" : "ENROLL_REQUIRED",
                activated ? "credential_registered,mfa_method=WebAuthn"
                    : "enrollment_or_method_changed");
        } catch (Exception ex) {
            audit(request, pending.user(), "DB_ERROR", ex.getClass().getSimpleName());
        }
    }

    private static void audit(final HttpServletRequest request, final String user,
            final String result, final String detail) {
        try {
            GraphicalMatrixRuntime.auditLogger().log(
                "WEBAUTHN_REGISTER_ACTIVATE", user, result, null, detail, request);
        } catch (Exception ex) {
            // Credential registration must not fail solely because audit logging is unavailable.
        }
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
