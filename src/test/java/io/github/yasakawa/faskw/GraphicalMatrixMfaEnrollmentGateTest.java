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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.Subject;

import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixMfaEnrollmentGateTest {
    @TempDir
    Path idpHome;

    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        resetRepositorySchemaInitialized();
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        jdbcUrl = "jdbc:h2:mem:mfa-gate-" + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        Files.writeString(conf.resolve("db.properties"),
            "graphicalmatrix.db.driver=org.h2.Driver\n"
            + "graphicalmatrix.db.url=" + jdbcUrl + "\n"
            + "graphicalmatrix.db.user=sa\n"
            + "graphicalmatrix.db.password=\n"
            + "graphicalmatrix.db.autoInit=true\n"
            + "graphicalmatrix.db.pool.enabled=false\n");
        Files.writeString(conf.resolve("graphicalmatrix.properties"),
            "graphicalmatrix.sequence.storage=plaintext\n");
        System.setProperty("idp.home", idpHome.toString());
        new GraphicalMatrixRepository(idpHome.toString()).findMfaSettings("missing");
    }

    @AfterEach
    void tearDown() {
        GraphicalMatrixDataSource.close();
        System.clearProperty("idp.home");
    }

    @Test
    void disabledEnrollmentIsDeniedEvenWhenGlobalPolicyBypassesMfa() throws Exception {
        writePolicy("bypass", "deny");
        insert("alice", "DISABLED", "GraphicalMatrix", "g1", 3L);
        final ProfileRequestContext context = context("alice");

        assertNull(new GraphicalMatrixMfaDecisionStrategy().apply(context));
        assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
            mfa(context).getEvent());
    }

    @Test
    void missingEnrollmentIsDeniedByDefault() throws Exception {
        writePolicy("bypass", "deny");
        final ProfileRequestContext context = context("alice");

        assertNull(new GraphicalMatrixMfaDecisionStrategy().apply(context));
        assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
            mfa(context).getEvent());
    }

    @Test
    void compatibilityPolicyAllowsOnlyExplicitBypassForMissingEnrollment() throws Exception {
        writePolicy("bypass", "allow-on-bypass");
        final ProfileRequestContext context = context("alice");

        assertNull(new GraphicalMatrixMfaDecisionStrategy().apply(context));
        assertNull(mfa(context).getEvent());
    }

    @Test
    void activeEnrollmentWithSupportedMethodCanUseConfiguredBypass() throws Exception {
        writePolicy("bypass", "deny");
        insert("alice", "ACTIVE", "GraphicalMatrix", "g1", 3L);
        final ProfileRequestContext context = context("alice");

        assertNull(new GraphicalMatrixMfaDecisionStrategy().apply(context));
        assertNull(mfa(context).getEvent());
    }

    @Test
    void activeEnrollmentWithUnsupportedMethodCannotUseBypass() throws Exception {
        writePolicy("bypass", "deny");
        insert("alice", "ACTIVE", "UnknownMethod", "g1", 3L);
        final ProfileRequestContext context = context("alice");

        assertNull(new GraphicalMatrixMfaDecisionStrategy().apply(context));
        assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
            mfa(context).getEvent());
    }

    @Test
    void activeEnrollmentSelectsConfiguredFactorAndCreatesCompletionGuard() throws Exception {
        writePolicy("require", "deny");
        insert("alice", "ACTIVE", "GraphicalMatrix", "g1", 7L);
        final ProfileRequestContext context = context("alice");

        assertEquals("authn/External", new GraphicalMatrixMfaDecisionStrategy().apply(context));
        final GraphicalMatrixMfaGuardContext guard =
            mfa(context).getSubcontext(GraphicalMatrixMfaGuardContext.class);
        assertNotNull(guard);
        assertEquals("alice", guard.user());
        assertEquals(7L, guard.stateVersion());
    }

    @Test
    void completionRejectsEnrollmentDisabledAfterFactorSelection() throws Exception {
        writePolicy("require", "deny");
        insert("alice", "ACTIVE", "GraphicalMatrix", "g1", 7L);
        final ProfileRequestContext context = context("alice");

        assertEquals("authn/External", new GraphicalMatrixMfaDecisionStrategy().apply(context));
        mfa(context).getActiveResults().put("authn/External", result("authn/External", "alice"));
        updateStatus("alice", "DISABLED");

        assertNull(new GraphicalMatrixMfaCompletionStrategy().apply(context));
        assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
            mfa(context).getEvent());
    }

    @Test
    void completionRejectsSecondFactorForDifferentUser() throws Exception {
        writePolicy("require", "deny");
        insert("alice", "ACTIVE", "GraphicalMatrix", "g1", 7L);
        final ProfileRequestContext context = context("alice");

        assertEquals("authn/External", new GraphicalMatrixMfaDecisionStrategy().apply(context));
        mfa(context).getActiveResults().put("authn/External", result("authn/External", "bob"));

        assertNull(new GraphicalMatrixMfaCompletionStrategy().apply(context));
        assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
            mfa(context).getEvent());
    }

    private void writePolicy(final String defaultPolicy, final String missingPolicy) throws Exception {
        Files.writeString(idpHome.resolve("conf/graphicalmatrix/mfa-policy.properties"),
            "graphicalmatrix.mfa.default=" + defaultPolicy + "\n"
            + "graphicalmatrix.mfa.missingEnrollmentPolicy=" + missingPolicy + "\n");
    }

    private void insert(final String user, final String status, final String method,
            final String sequence, final long version) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO graphicalmatrix_enrollment "
                 + "(user_id, sequence, initial_sequence, status, mfa_method, state_version, created_at, updated_at) "
                 + "VALUES (?, ?, ?, ?, ?, ?, 1, 1)")) {
            ps.setString(1, user);
            ps.setString(2, sequence);
            ps.setString(3, sequence);
            ps.setString(4, status);
            ps.setString(5, method);
            ps.setLong(6, version);
            ps.executeUpdate();
        }
    }

    private void updateStatus(final String user, final String status) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE graphicalmatrix_enrollment SET status = ?, "
                 + "state_version = state_version + 1 WHERE user_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, user);
            ps.executeUpdate();
        }
    }

    private static ProfileRequestContext context(final String user) {
        final ProfileRequestContext context = new ProfileRequestContext();
        final AuthenticationContext authentication = new AuthenticationContext();
        final MultiFactorAuthenticationContext mfa = new MultiFactorAuthenticationContext();
        mfa.getActiveResults().put("authn/Password", result("authn/Password", user));
        authentication.addSubcontext(mfa);
        context.addSubcontext(authentication);
        return context;
    }

    private static AuthenticationResult result(final String flow, final String user) {
        final Subject subject = new Subject();
        subject.getPrincipals().add(new UsernamePrincipal(user));
        return new AuthenticationResult(flow, subject);
    }

    private static MultiFactorAuthenticationContext mfa(final ProfileRequestContext context) {
        return context.getSubcontext(AuthenticationContext.class)
            .getSubcontext(MultiFactorAuthenticationContext.class);
    }

    private static void resetRepositorySchemaInitialized() throws Exception {
        final Field field = GraphicalMatrixRepository.class.getDeclaredField("SCHEMA_INITIALIZED");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(null)).set(false);
    }
}
