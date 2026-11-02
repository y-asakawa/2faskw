/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensaml.profile.context.ProfileRequestContext;

class GraphicalMatrixMfaCompletionAuditTest {
    @TempDir Path home;
    private final GraphicalMatrixMfaSuccessRecord.Expectation expected =
        new GraphicalMatrixMfaSuccessRecord.Expectation("sample01", "TOTP", "authn/TOTP", 7L);
    private final GraphicalMatrixMfaSuccessRecord.Result result = new GraphicalMatrixMfaSuccessRecord.Result(
        GraphicalMatrixMfaSuccessRecord.StateVerdict.VALID, GraphicalMatrixMfaSuccessRecord.StoreStatus.UPDATED,
        1700000000123L, null, false);
    private static final String ID = "12345678-1234-4234-8234-123456789abc";

    @Test void emitterMatchesSharedDashboardFixtureExactly() throws Exception {
        Files.createDirectories(home.resolve("logs"));
        final String detail = GraphicalMatrixMfaCompletionAudit.encode(expected, result, ID, "sso",
            "https://sp.example.org/a_b?x=a+b&y=%25");
        assertTrue(new GraphicalMatrixAuditLogger(home.toString()).logCompletion("sample01", detail, null, result.completedAtMillis()));
        assertEquals(Files.readString(Path.of("test-fixtures/mfa-completion-v1.txt")).strip(),
            Files.readString(home.resolve("logs/graphicalmatrix-audit.log")).strip());
    }

    @Test void writerFailureIsReportedAndOversizedDetailRejected() {
        assertFalse(new GraphicalMatrixAuditLogger(home.toString()).logCompletion("sample01", "mfa1:test", null, 1L));
        assertThrows(IllegalArgumentException.class, () ->
            GraphicalMatrixMfaCompletionAudit.encode(expected, result, ID, "sso", "x".repeat(16384)));
    }

    @Test void knownSsoAndUnknownProfilesRemainDistinct() {
        final ProfileRequestContext input = new ProfileRequestContext();
        input.setProfileId("http://shibboleth.net/ns/profiles/saml2/sso/browser");
        assertEquals(GraphicalMatrixMfaCompletionAudit.encode(expected, result, ID, "sso", "-"),
            GraphicalMatrixMfaCompletionAudit.detail(input, expected, result, ID));
        input.setProfileId("unknown");
        assertEquals(GraphicalMatrixMfaCompletionAudit.encode(expected, result, ID, "other", "-"),
            GraphicalMatrixMfaCompletionAudit.detail(input, expected, result, ID));
    }
}
