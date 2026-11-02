/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw.dashboard;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Only non-secret completion fields are forwarded; the source Requester is deliberately discarded. */
public record MfaCompletionInfo(String method, String flow, String scope, long completedAt,
        String store, String eventId, long stateVersion) {
    private static final Set<String> KEYS = Set.of("method", "flow", "scope", "sp", "completedAt", "store", "eventId", "stateVersion");
    private static final Map<String, String> FLOWS = Map.of("GraphicalMatrix", "authn/External", "TOTP", "authn/TOTP", "WebAuthn", "authn/WebAuthn");

    public MfaCompletionInfo {
        if (method == null || !flowEquals(method, flow)
                || !Set.of("sso", "self-service", "other").contains(scope != null ? scope : "")
                || !Set.of("UPDATED", "UNCHANGED", "DISABLED", "SKIPPED_CONTENDED", "FAILED").contains(store != null ? store : "")
                || completedAt < 0 || stateVersion < 0 || eventId == null
                || !UUID.fromString(eventId).toString().equals(eventId)) {
            throw new IllegalArgumentException("invalid MFA completion fields");
        }
    }

    private static boolean flowEquals(final String method, final String flow) {
        return FLOWS.containsKey(method) && FLOWS.get(method).equals(flow);
    }

    static MfaCompletionInfo decode(final String detail) {
        try {
            if (detail == null || !detail.startsWith("mfa1:") || detail.length() > 16 * 1024) {
                throw new IllegalArgumentException("invalid MFA completion detail version/size");
            }
            final String encoded = detail.substring(5);
            final byte[] bytes = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
                throw new IllegalArgumentException("non-canonical Base64");
            }
            final String form = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            final Map<String, String> fields = new LinkedHashMap<>();
            for (final String pair : form.split("&", -1)) {
                final int equals = pair.indexOf('=');
                if (equals < 1) { throw new IllegalArgumentException("invalid completion form"); }
                final String key = pair.substring(0, equals);
                final String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                if (!KEYS.contains(key) || value.isEmpty() || value.chars().anyMatch(Character::isISOControl)
                        || !URLEncoder.encode(value, StandardCharsets.UTF_8).equals(pair.substring(equals + 1))
                        || fields.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("invalid/duplicate completion field");
                }
            }
            if (!fields.keySet().equals(KEYS)) { throw new IllegalArgumentException("missing completion fields"); }
            return new MfaCompletionInfo(fields.get("method"), fields.get("flow"), fields.get("scope"),
                number(fields.get("completedAt")), fields.get("store"), fields.get("eventId"), number(fields.get("stateVersion")));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid MFA completion detail");
        }
    }

    private static long number(final String value) {
        if (!value.matches("[0-9]{1,19}")) { throw new IllegalArgumentException("invalid completion number"); }
        return Long.parseLong(value);
    }
}
