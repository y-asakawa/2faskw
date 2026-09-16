/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixSecurityRegressionTest {
    @Test
    void passwordAuthenticationCannotStartTotpEnrollment() throws Exception {
        final String startServlet = Files.readString(Path.of(
            "src/main/java/io/github/yasakawa/faskw/GraphicalMatrixStartServlet.java"));
        final String changeServlet = Files.readString(Path.of(
            "src/main/java/io/github/yasakawa/faskw/GraphicalMatrixChangeServlet.java"));
        final String verifyServlet = Files.readString(Path.of(
            "src/main/java/io/github/yasakawa/faskw/GraphicalMatrixVerifyServlet.java"));

        assertFalse(startServlet.contains("beginTotpRegistration"));
        assertTrue(changeServlet.contains("repository.beginTotpRegistration"));
        assertTrue(changeServlet.contains("GraphicalMatrixTotpEnrollmentSession.initialize"));
        assertTrue(verifyServlet.contains("GraphicalMatrixTotpEnrollmentSession.claim"));
        assertTrue(verifyServlet.contains("repository.verifyTotpRegistration"));
    }

    @Test
    void webAuthnMethodRequiresAnExistingOrNewlyRegisteredCredential()
            throws Exception {
        final String changeServlet = source("GraphicalMatrixChangeServlet.java");
        final String hook = source("GraphicalMatrixWebAuthnRegistrationHook.java");
        final String changeMethodView = Files.readString(Path.of("views/change-method.html"));
        final String postconfig = Files.readString(Path.of(
            "src/main/resources/META-INF/net.shibboleth.idp/postconfig.xml"));

        assertTrue(changeServlet.contains("beginWebAuthnRegistration"));
        assertTrue(changeServlet.contains("GraphicalMatrixWebAuthnCredentialLookup.lookup"));
        assertTrue(changeServlet.contains("existing_credential,mfa_method=WebAuthn"));
        assertTrue(changeServlet.contains(
            "GraphicalMatrixWebAuthnRegistrationSession.initialize"));
        assertTrue(hook.contains("activateWebAuthnIfMethodCurrent"));
        assertTrue(postconfig.contains(
            "shibboleth.authn.WebAuthn.audit.AddKeyAuditSuccessHook"));
        assertTrue(changeMethodView.contains("登録済みcredentialがあればそのまま方式を変更"));
        assertTrue(changeMethodView.contains("Add new security key"));
    }

    @Test
    void adminAndStorageMigrationsUseDatabaseConcurrencyGuards() throws Exception {
        final String adminApi = source("GraphicalMatrixAdminApiServlet.java");
        final String sequenceMigration = source("GraphicalMatrixSequenceMigrationTool.java");
        final String totpMigration = source("GraphicalMatrixTotpSeedMigrationTool.java");

        assertTrue(adminApi.contains("FOR UPDATE"));
        assertTrue(sequenceMigration.contains("AND state_version = ?"));
        assertTrue(sequenceMigration.contains("state_version = state_version + 1"));
        assertTrue(totpMigration.contains("AND state_version = ? AND totp_seed = ?"));
        assertTrue(totpMigration.contains("state_version = state_version + 1"));
    }

    @Test
    void physicalDeleteFailsClosedAndScopesWebAuthnCleanup() throws Exception {
        final String adminApi = source("GraphicalMatrixAdminApiServlet.java");
        final String dbCli = Files.readString(Path.of("graphicalmatrix-db.sh"));

        assertTrue(adminApi.contains("SET status = 'DISABLED'"));
        assertTrue(adminApi.contains("WHERE user_id = ? AND status = 'DISABLED'"));
        assertTrue(adminApi.contains("net.shibboleth.idp.plugin.authn.webauthn"));
        assertTrue(adminApi.contains("WebAuthn credential cleanup verification failed"));
        assertTrue(adminApi.contains("method = normalizeMethod(row.mfaMethod)"));
        assertTrue(dbCli.contains("missingEnrollmentPolicy=allow-on-bypass"));
        assertTrue(dbCli.contains("normalized_method=\"$(normalize_method \"$method\")\""));
        assertTrue(dbCli.contains("WHERE s.context = 'net.shibboleth.idp.plugin.authn.webauthn'"));
        assertTrue(dbCli.contains("enrollment remains DISABLED"));
        assertTrue(dbCli.contains("uses D in standard mode"));
    }

    @Test
    void mfaFlowConfigurationRechecksAllSecondFactors() throws Exception {
        final String mfaConfig = Files.readString(Path.of("mfa-authn-config.xml"));
        final String events = Files.readString(Path.of("examples/authn-events-flow.xml"));

        assertTrue(mfaConfig.contains("key=\"authn/External\""));
        assertTrue(mfaConfig.contains("key=\"authn/TOTP\""));
        assertTrue(mfaConfig.contains("key=\"authn/WebAuthn\""));
        assertTrue(mfaConfig.contains("GraphicalMatrixMfaCompletionStrategy"));
        assertTrue(events.contains("GraphicalMatrixAccessDenied"));
        assertTrue(events.contains("GraphicalMatrixServiceUnavailable"));
    }

    @Test
    void legacyChangeOnlyAcceptsTheCurrentlySelectedGraphicalMatrixMethod() {
        assertTrue(GraphicalMatrixChangeServlet.legacyGraphicalMatrixAllowed(
            enrollment("GraphicalMatrix")));
        assertTrue(GraphicalMatrixChangeServlet.legacyGraphicalMatrixAllowed(
            enrollment("MFA:GraphicalMatrix")));
        assertFalse(GraphicalMatrixChangeServlet.legacyGraphicalMatrixAllowed(enrollment("TOTP")));
        assertFalse(GraphicalMatrixChangeServlet.legacyGraphicalMatrixAllowed(enrollment("WebAuthn")));
    }

    @Test
    void legacyUserIdentifierHasABoundedLength() {
        assertTrue(GraphicalMatrixChangeServlet.validUser("a".repeat(255)));
        assertFalse(GraphicalMatrixChangeServlet.validUser("a".repeat(256)));
    }

    @Test
    void auditCorrelationIdIsStableWithoutExposingTheContainerSessionId() {
        final HttpSession session = session("live-container-session-id");

        final String first = GraphicalMatrixAuditLogger.auditCorrelationId(session);
        final String second = GraphicalMatrixAuditLogger.auditCorrelationId(session);

        assertEquals(first, second);
        assertNotEquals("live-container-session-id", first);
        assertFalse(first.isBlank());
    }

    private static GraphicalMatrixEnrollment enrollment(final String method) {
        return new GraphicalMatrixEnrollment("g1", "ACTIVE", 0, 0L, false, 1L, method);
    }

    private static String source(final String filename) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/yasakawa/faskw", filename));
    }

    private static HttpSession session(final String id) {
        final Map<String, Object> attributes = new HashMap<>();
        return (HttpSession) Proxy.newProxyInstance(
            HttpSession.class.getClassLoader(),
            new Class<?>[] {HttpSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "getAttribute" -> attributes.get((String) args[0]);
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
