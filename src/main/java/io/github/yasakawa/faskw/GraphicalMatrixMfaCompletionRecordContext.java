/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.util.UUID;
import net.shibboleth.idp.authn.AuthenticationResult;
import org.opensaml.messaging.context.BaseContext;

/** Request-local bookkeeping, never a credential or a self-service authorization receipt. */
final class GraphicalMatrixMfaCompletionRecordContext extends BaseContext {
    final String eventId = UUID.randomUUID().toString();
    boolean attempted;
    boolean auditAttempted;
    GraphicalMatrixMfaSuccessRecord.Result result;
    private GraphicalMatrixMfaSuccessRecord.Expectation expectation;
    private AuthenticationResult password;
    private AuthenticationResult factor;
    private String profile;

    void bind(final GraphicalMatrixMfaSuccessRecord.Expectation expected, final AuthenticationResult pw,
            final AuthenticationResult second, final String profileId) {
        attempted = true;
        expectation = expected;
        password = pw;
        factor = second;
        profile = profileId;
    }

    boolean matches(final GraphicalMatrixMfaSuccessRecord.Expectation expected,
            final AuthenticationResult pw, final AuthenticationResult second, final String profileId) {
        return expected.equals(expectation) && pw == password && second == factor
            && java.util.Objects.equals(profile, profileId);
    }
}
