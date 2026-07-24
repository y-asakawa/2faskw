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

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.opensaml.profile.action.ActionSupport;
import org.opensaml.profile.action.EventIds;
import org.opensaml.profile.context.ProfileRequestContext;

import net.shibboleth.idp.profile.AbstractProfileAction;
import net.shibboleth.shared.servlet.impl.HttpServletRequestResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates a short-lived handoff after the IdP has completed fresh Password plus MFA authentication. */
public final class InitializeGraphicalMatrixSelfService extends AbstractProfileAction {
    private static final Logger LOG = LoggerFactory.getLogger(InitializeGraphicalMatrixSelfService.class);
    static final String CHANGE_REDIRECT_URL =
        "contextRelative:/graphicalmatrix/change?mode=idp-self-service";

    @Override
    protected void doExecute(final ProfileRequestContext profileRequestContext) {
        final HttpServletRequest request = HttpServletRequestResponseContext.getRequest();
        final GraphicalMatrixSelfServiceAuthentication.ValidationResult validation =
            GraphicalMatrixSelfServiceAuthentication.validate(profileRequestContext);
        if (request == null || !validation.isValid()) {
            final String reason = request == null
                ? "http_request_unavailable" : validation.getFailureReason();
            LOG.warn("2FAS-KW self-service authentication result was rejected: reason={}",
                reason);
            audit(request, validation.getUser(), "DENIED", reason);
            ActionSupport.buildEvent(profileRequestContext, EventIds.ACCESS_DENIED);
            return;
        }

        final GraphicalMatrixConfig config;
        try {
            config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        } catch (Exception ex) {
            audit(request, validation.getUser(), "CONFIG_ERROR", ex.getClass().getSimpleName());
            ActionSupport.buildEvent(profileRequestContext, EventIds.ACCESS_DENIED);
            return;
        }
        if (!config.isSelfServiceEnabled()) {
            audit(request, validation.getUser(), "DENIED", "self_service_disabled");
            ActionSupport.buildEvent(profileRequestContext, EventIds.ACCESS_DENIED);
            return;
        }

        final HttpSession session = request.getSession(true);
        request.changeSessionId();
        final long expiresAt = System.currentTimeMillis() + config.getSelfServiceTransactionMillis();
        GraphicalMatrixSelfServiceSession.initialize(session, validation.getUser(), expiresAt);
        getRequestContext(profileRequestContext).getFlowScope().put(
            "redirectUrl", CHANGE_REDIRECT_URL);

        final Instant completedAt = validation.getCompletedAt();
        audit(request, validation.getUser(), "OK",
            "factor=" + validation.getSecondFactor()
                + (completedAt != null ? ",authn_completed=" + completedAt : ""));
    }

    private static void audit(final HttpServletRequest request, final String user,
            final String result, final String detail) {
        try {
            GraphicalMatrixRuntime.auditLogger().log(
                "SELF_SERVICE_AUTH", user, result, null, detail, request);
        } catch (Exception ex) {
            // Authentication must not fail solely because audit logging is unavailable.
        }
    }
}
