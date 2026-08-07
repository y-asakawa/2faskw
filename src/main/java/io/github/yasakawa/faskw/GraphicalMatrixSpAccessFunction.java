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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.shibboleth.idp.attribute.IdPAttribute;
import net.shibboleth.idp.attribute.IdPAttributeValue;
import net.shibboleth.idp.attribute.context.AttributeContext;
import net.shibboleth.idp.authn.context.SubjectContext;
import net.shibboleth.profile.context.RelyingPartyContext;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ContextCheck function enforcing SP-specific IdP attribute access policies. */
public final class GraphicalMatrixSpAccessFunction
        implements Function<ProfileRequestContext, String> {
    private static final Logger LOG =
        LoggerFactory.getLogger(GraphicalMatrixSpAccessFunction.class);
    private static final String PROCEED = "proceed";
    private static final String DENIED = "ContextCheckDenied";

    private final GraphicalMatrixSpManagementConfig config;
    private final GraphicalMatrixSpAccessPolicyStore store;
    private final GraphicalMatrixSpAccessEvaluator evaluator =
        new GraphicalMatrixSpAccessEvaluator();
    private final Object auditLock = new Object();

    public GraphicalMatrixSpAccessFunction(final String idpHome) throws IOException {
        config = GraphicalMatrixSpManagementConfig.load(idpHome);
        store = config.accessEnabled() ? new GraphicalMatrixSpAccessPolicyStore(config) : null;
    }

    @Override
    public String apply(final ProfileRequestContext input) {
        if (!config.accessEnabled() || input == null) {
            return PROCEED;
        }
        final RelyingPartyContext relyingParty =
            input.getSubcontext(RelyingPartyContext.class);
        final String entityId = relyingParty == null ? null : relyingParty.getRelyingPartyId();
        if (entityId == null || entityId.isBlank()) {
            return PROCEED;
        }
        final GraphicalMatrixSpAccessPolicy.Policy policy = store.policyFor(entityId);
        if (policy == null || !policy.enabled()) {
            return PROCEED;
        }
        final AttributeContext attributeContext = relyingParty.getSubcontext(AttributeContext.class);
        if (attributeContext == null || attributeContext.getUnfilteredIdPAttributes() == null) {
            audit(input, policy, "DENY", "ATTRIBUTE_CONTEXT_MISSING");
            return DENIED;
        }
        final GraphicalMatrixSpAccessEvaluator.Decision decision =
            evaluator.evaluate(policy, entityId,
                values(attributeContext.getUnfilteredIdPAttributes()));
        final boolean allowed =
            decision.result() == GraphicalMatrixSpAccessEvaluator.Result.ALLOW;
        audit(input, policy, allowed ? "ALLOW" : "DENY", decision.reason());
        return allowed ? PROCEED : DENIED;
    }

    private static Map<String, List<String>> values(
            final Map<String, IdPAttribute> source) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (final IdPAttribute attribute : source.values()) {
            final List<String> values = new ArrayList<>();
            for (final IdPAttributeValue item : attribute.getValues()) {
                final Object nativeValue = item.getNativeValue();
                final String value = nativeValue instanceof String text
                    ? text : item.getDisplayValue();
                if (value != null) {
                    values.add(value);
                }
            }
            out.put(attribute.getId(), List.copyOf(values));
        }
        return Map.copyOf(out);
    }

    private void audit(final ProfileRequestContext input,
            final GraphicalMatrixSpAccessPolicy.Policy policy, final String result,
            final String reason) {
        if (!config.accessAuditDecisions()) {
            return;
        }
        final SubjectContext subject = input.getSubcontext(SubjectContext.class);
        final String principal = subject == null || subject.getPrincipalName() == null
            ? "-" : sanitize(subject.getPrincipalName());
        final String line = "ts=" + Instant.now() + " event=ACCESS_POLICY_" + result
            + " user=" + principal + " sp=" + sanitize(policy.entityId())
            + " policy_revision=" + policy.revision() + " reason=" + reason
            + System.lineSeparator();
        synchronized (auditLock) {
            try {
                Files.createDirectories(config.accessAuditLogPath().getParent());
                Files.writeString(config.accessAuditLogPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                try {
                    Files.setPosixFilePermissions(config.accessAuditLogPath(),
                        PosixFilePermissions.fromString("rw-r-----"));
                } catch (UnsupportedOperationException ignored) {
                    // Production Linux filesystems expose POSIX permissions.
                }
            } catch (IOException ex) {
                LOG.error("Unable to write 2FAS-KW access decision audit event", ex);
            }
        }
    }

    private static String sanitize(final String value) {
        return value.replaceAll("[\\r\\n\\t ]+", "_");
    }
}
