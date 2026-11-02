/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.shibboleth.profile.context.RelyingPartyContext;
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixMfaCompletionAudit {
    private static final Set<String> SSO_PROFILES = Set.of(
        "http://shibboleth.net/ns/profiles/saml2/sso/browser",
        "http://shibboleth.net/ns/profiles/saml1/sso/browser");

    private GraphicalMatrixMfaCompletionAudit() { }

    static String detail(final ProfileRequestContext input,
            final GraphicalMatrixMfaSuccessRecord.Expectation expected,
            final GraphicalMatrixMfaSuccessRecord.Result result, final String eventId) {
        final RelyingPartyContext rp = input.getSubcontext(RelyingPartyContext.class);
        final String sp = rp != null && rp.getRelyingPartyId() != null && !rp.getRelyingPartyId().isBlank()
            ? rp.getRelyingPartyId() : "-";
        final String scope = GraphicalMatrixMfaDecisionStrategy.isSelfServiceProfile(input) ? "self-service"
            : SSO_PROFILES.contains(input.getProfileId() != null ? input.getProfileId() : "") ? "sso" : "other";
        return encode(expected, result, eventId, scope, sp);
    }

    static String encode(final GraphicalMatrixMfaSuccessRecord.Expectation expected,
            final GraphicalMatrixMfaSuccessRecord.Result result, final String eventId,
            final String scope, final String sp) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("method", switch (expected.method()) {
            case "GRAPHICALMATRIX" -> "GraphicalMatrix";
            case "TOTP" -> "TOTP";
            case "WEBAUTHN" -> "WebAuthn";
            default -> throw new IllegalArgumentException("Unknown MFA method");
        });
        fields.put("flow", expected.flow());
        fields.put("scope", scope);
        fields.put("sp", sp);
        fields.put("completedAt", String.valueOf(result.completedAtMillis()));
        fields.put("store", result.recordStatus().name());
        fields.put("eventId", eventId);
        fields.put("stateVersion", String.valueOf(expected.stateVersion()));
        final String body = fields.entrySet().stream().map(entry -> entry.getKey() + "="
            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        final String detail = "mfa1:" + Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        if (detail.length() > 16 * 1024) { throw new IllegalArgumentException("MFA completion detail exceeds limit"); }
        return detail;
    }
}
