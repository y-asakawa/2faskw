/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.security.auth.Subject;
import net.shibboleth.idp.authn.AuthenticationResult;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext;
import net.shibboleth.idp.authn.context.SubjectContext;
import net.shibboleth.idp.authn.context.SubjectCanonicalizationContext;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import net.shibboleth.idp.authn.principal.TOTPPrincipal;
import net.shibboleth.idp.plugin.authn.totp.context.TOTPContext;
import net.shibboleth.idp.plugin.authn.totp.impl.TOTPCredentialValidator;
import net.shibboleth.shared.codec.Base32Support;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixMfaCompletionIntegrationTest {
    @TempDir Path home;
    private String url;
    private String previousHome;
    private final GraphicalMatrixMfaSuccessRecordOptions on = new GraphicalMatrixMfaSuccessRecordOptions(true, true, 1);

    @BeforeEach void prepare() throws Exception {
        previousHome = System.getProperty("idp.home");
        System.setProperty("idp.home", home.toString());
        Files.createDirectories(home.resolve("conf/graphicalmatrix"));
        Files.createDirectories(home.resolve("logs"));
        url = "jdbc:h2:mem:completion-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        dbUser("sa", "");
        options(true, true);
        try (Connection c = connection()) {
            GraphicalMatrixSequenceMigrationTool.initSchema(c);
        }
        sql("INSERT INTO graphicalmatrix_enrollment (user_id,sequence,initial_sequence,status,mfa_method,totp_status,totp_seed,"
            + "state_version,created_at,updated_at) VALUES ('alice','g1','g1','ACTIVE','GraphicalMatrix','ACTIVE','test-only-seed',7,100,100)");
    }

    @AfterEach void cleanup() {
        GraphicalMatrixDataSource.close();
        if (previousHome == null) { System.clearProperty("idp.home"); }
        else { System.setProperty("idp.home", previousHome); }
    }

    @Test void allThreeMethodsRecordOnlyTimestampColumnsAndRemainMonotonic() throws Exception {
        for (final String method : List.of("GraphicalMatrix", "TOTP", "WebAuthn")) {
            sql("UPDATE graphicalmatrix_enrollment SET mfa_method='MFA:" + method + "', status=' ACTIVE ', last_success_at=0");
            final Map<String, String> before = row();
            final var result = repository(2000).checkAndRecordMfaCompletion(expected(method), on);
            assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.UPDATED, result.recordStatus());
            assertEquals("2000", row().get("last_success_at"));
            assertEquals("2000", row().get("updated_at"));
            final Map<String, String> after = row();
            for (final String key : before.keySet()) {
                if (!List.of("last_success_at", "updated_at").contains(key)) { assertEquals(before.get(key), after.get(key), key); }
            }
            final var old = repository(1500).checkAndRecordMfaCompletion(expected(method), on);
            assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.UNCHANGED, old.recordStatus());
            assertEquals("2000", row().get("last_success_at"));
        }
    }

    @Test void completionReevaluationDoesNotWriteOrLogTwiceButStillRejectsAReset() throws Exception {
        final ProfileRequestContext prc = context("GraphicalMatrix");
        final var strategy = new GraphicalMatrixMfaCompletionStrategy();
        strategy.apply(prc);
        final Map<String, String> after = row();
        assertNotNull(prc.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
        final var validation = GraphicalMatrixSelfServiceAuthentication.validate(prc);
        assertTrue(validation.isValid());
        assertEquals(7, validation.getStateVersion());
        assertFalse(GraphicalMatrixSelfServiceAuthentication.validate(prc).isValid());
        strategy.apply(prc);
        assertEquals(after, row());
        assertEquals(1, Files.readAllLines(home.resolve("logs/graphicalmatrix-audit.log")).size());
        sql("UPDATE graphicalmatrix_enrollment SET state_version=state_version+1");
        strategy.apply(prc);
        assertNull(prc.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
        assertNotNull(GraphicalMatrixMfaSubjectSupport.mfaContext(prc).getEvent());
        assertEquals(after.get("last_success_at"), row().get("last_success_at"));
    }

    @Test void allFlagCombinationsKeepTheSameSelfServiceGenerationAndFreshResultBinding() throws Exception {
        for (final String method : List.of("GraphicalMatrix", "TOTP", "WebAuthn")) {
            for (final boolean record : List.of(false, true)) {
                for (final boolean audit : List.of(false, true)) {
                    options(record, audit);
                    sql("UPDATE graphicalmatrix_enrollment SET mfa_method='" + method + "', last_success_at=0");
                    Files.deleteIfExists(home.resolve("logs/graphicalmatrix-audit.log"));
                    final var prc = context(method);
                    new GraphicalMatrixMfaCompletionStrategy().apply(prc);
                    final var validated = GraphicalMatrixSelfServiceAuthentication.validate(prc);
                    assertTrue(validated.isValid());
                    assertEquals(7L, validated.getStateVersion());
                    assertEquals(record, Long.parseLong(row().get("last_success_at")) > 0);
                    assertEquals(audit, Files.exists(home.resolve("logs/graphicalmatrix-audit.log")));
                }
            }
        }
    }

    @Test void officialTotpSubjectCompletesAuthenticationAndRecordsSuccess() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='TOTP'");
        final var prc = context("TOTP");
        final var mfa = GraphicalMatrixMfaSubjectSupport.mfaContext(prc);
        final Subject identity = new OfficialTotpValidator().subjectFor(prc, "alice");
        assertTrue(identity.getPrincipals(UsernamePrincipal.class).isEmpty());
        assertEquals(1, identity.getPrincipals(TOTPPrincipal.class).size());
        final var factor = new AuthenticationResult("authn/TOTP", identity);
        factor.setAuthenticationInstant(Instant.now());
        mfa.getActiveResults().put("authn/TOTP", factor);
        new GraphicalMatrixMfaCompletionStrategy().apply(prc);
        assertNull(mfa.getEvent());
        assertTrue(Long.parseLong(row().get("last_success_at")) > 0);
        assertTrue(GraphicalMatrixSelfServiceAuthentication.validate(prc).isValid());
        assertEquals(1, Files.readAllLines(home.resolve("logs/graphicalmatrix-audit.log")).size());
    }

    @Test void totpSubjectMismatchAmbiguityAndWrongFlowNeverRecordOrAuthorize() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='TOTP'");
        for (final var result : List.of(
                GraphicalMatrixMfaSubjectSupportTest.result("authn/TOTP", new TOTPPrincipal("bob")),
                GraphicalMatrixMfaSubjectSupportTest.result("authn/TOTP", new TOTPPrincipal("alice"), new TOTPPrincipal("bob")),
                GraphicalMatrixMfaSubjectSupportTest.result("authn/TOTP", new TOTPPrincipal("alice"), new UsernamePrincipal("bob")),
                GraphicalMatrixMfaSubjectSupportTest.result("authn/WebAuthn", new UsernamePrincipal("alice")))) {
            final var input = context("TOTP");
            final var mfa = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
            mfa.getActiveResults().put("authn/TOTP", result);
            new GraphicalMatrixMfaCompletionStrategy().apply(input);
            assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT, mfa.getEvent());
            assertEquals("0", row().get("last_success_at"));
            assertNull(input.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
            assertFalse(Files.exists(home.resolve("logs/graphicalmatrix-audit.log")));
        }
    }

    @Test void totpCompletionStillRejectsAdministrativeVersionChange() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='TOTP'");
        final var input = context("TOTP");
        sql("UPDATE graphicalmatrix_enrollment SET state_version=8");
        new GraphicalMatrixMfaCompletionStrategy().apply(input);
        assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT, GraphicalMatrixMfaSubjectSupport.mfaContext(input).getEvent());
        assertEquals("0", row().get("last_success_at"));
        assertNull(input.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
    }

    @Test void officialWebauthnSecondFactorCompletesAndRecordsOnlyOnce() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='WebAuthn'");
        final var input = context("WebAuthn");
        final var mfa = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
        assertTrue(mfa.getActiveResults().get("authn/WebAuthn").getSubject()
            .getPrincipals(UsernamePrincipal.class).isEmpty());
        final var completion = new GraphicalMatrixMfaCompletionStrategy();
        completion.apply(input);
        assertNull(mfa.getEvent());
        assertTrue(Long.parseLong(row().get("last_success_at")) > 0);
        assertTrue(GraphicalMatrixSelfServiceAuthentication.validate(input).isValid());
        final var after = row();
        completion.apply(input);
        assertNull(mfa.getEvent());
        assertEquals(after, row());
        assertEquals(1, Files.readAllLines(home.resolve("logs/graphicalmatrix-audit.log")).size());
    }

    @Test void officialWebauthnSsoRecordsSuccessWithoutSelfServiceAuthorization() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='WebAuthn'");
        final var input = context("WebAuthn");
        input.setProfileId("urn:test:sso");
        new GraphicalMatrixMfaCompletionStrategy().apply(input);
        assertNull(GraphicalMatrixMfaSubjectSupport.mfaContext(input).getEvent());
        assertTrue(Long.parseLong(row().get("last_success_at")) > 0);
        assertNull(input.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
        assertEquals(1, Files.readAllLines(home.resolve("logs/graphicalmatrix-audit.log")).size());
    }

    @Test void webauthnWrongUserUnboundCanonicalNameAndPreviousResultNeverAuthorize() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='WebAuthn'");
        for (final String failure : List.of("wrong-user", "password-context", "previous", "missing", "wrong-flow")) {
            final var input = context("WebAuthn");
            final var mfa = GraphicalMatrixMfaSubjectSupport.mfaContext(input);
            final var factor = mfa.getActiveResults().get("authn/WebAuthn");
            final var canonical = input.getSubcontext(SubjectCanonicalizationContext.class);
            switch (failure) {
                case "wrong-user" -> mfa.getActiveResults().put("authn/WebAuthn",
                    OfficialWebAuthnTestSupport.result(input, "bob", true));
                case "password-context" -> canonical.setSubject(mfa.getActiveResults().get("authn/Password").getSubject());
                case "previous" -> factor.setPreviousResult(true);
                case "missing" -> input.removeSubcontext(SubjectCanonicalizationContext.class);
                case "wrong-flow" -> mfa.getActiveResults().put("authn/WebAuthn",
                    new AuthenticationResult("authn/TOTP", factor.getSubject()));
                default -> fail("unknown case");
            }
            new GraphicalMatrixMfaCompletionStrategy().apply(input);
            assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT, mfa.getEvent(), failure);
            assertEquals("0", row().get("last_success_at"));
            assertNull(input.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
            assertFalse(Files.exists(home.resolve("logs/graphicalmatrix-audit.log")));
        }
    }

    @Test void officialWebauthnCompletionStillRejectsChangedEnrollment() throws Exception {
        sql("UPDATE graphicalmatrix_enrollment SET mfa_method='WebAuthn'");
        for (final String update : List.of("state_version=8", "status='DISABLED'", "mfa_method='GraphicalMatrix'")) {
            sql("UPDATE graphicalmatrix_enrollment SET state_version=7,status='ACTIVE',mfa_method='WebAuthn'");
            final var input = context("WebAuthn");
            sql("UPDATE graphicalmatrix_enrollment SET " + update);
            new GraphicalMatrixMfaCompletionStrategy().apply(input);
            assertEquals(GraphicalMatrixMfaDecisionStrategy.ACCESS_DENIED_EVENT,
                GraphicalMatrixMfaSubjectSupport.mfaContext(input).getEvent());
            assertEquals("0", row().get("last_success_at"));
            assertNull(input.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
            assertFalse(Files.exists(home.resolve("logs/graphicalmatrix-audit.log")));
        }
    }

    private static final class OfficialTotpValidator extends TOTPCredentialValidator {
        Subject subjectFor(final ProfileRequestContext input, final String user) {
            final var context = new TOTPContext();
            context.setUsername(user);
            return populateSubject(new Subject(), input, context);
        }
    }

    @Test void writePermissionFailureDoesNotRemoveValidSelfServiceEvidence() throws Exception {
        sql("CREATE USER reader PASSWORD 'test-only'");
        sql("GRANT SELECT ON graphicalmatrix_enrollment TO reader");
        dbUser("reader", "test-only");
        final var prc = context("GraphicalMatrix");
        new GraphicalMatrixMfaCompletionStrategy().apply(prc);
        assertTrue(GraphicalMatrixSelfServiceAuthentication.validate(prc).isValid());
        assertEquals("0", row().get("last_success_at"));
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED,
            prc.getSubcontext(GraphicalMatrixMfaCompletionRecordContext.class).result.recordStatus());
    }

    @Test void readFailureAndChangedSubjectDenyBeforeAnySuccessEvent() throws Exception {
        final var prc = context("GraphicalMatrix");
        sql("DROP TABLE graphicalmatrix_enrollment");
        new GraphicalMatrixMfaCompletionStrategy().apply(prc);
        assertNull(prc.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
        assertFalse(Files.exists(home.resolve("logs/graphicalmatrix-audit.log")));
    }

    @Test void imageVerificationAloneDoesNotUpdateLastSuccess() throws Exception {
        assertTrue(new GraphicalMatrixRepository(home.toString()).verify("alice", List.of("g1"),
            List.of("g1", "g2"), 1000L, new GraphicalMatrixLockoutPolicy(3, 1000L, 6, 10000L), true, false).isSuccess());
        assertEquals("0", row().get("last_success_at"));
        assertEquals("7", row().get("state_version"));
    }

    @Test void optionalTimestampSchemaFailureDoesNotBecomeAMandatoryStateFailure() throws Exception {
        sql("ALTER TABLE graphicalmatrix_enrollment DROP COLUMN last_success_at");
        final var result = repository(2000).checkAndRecordMfaCompletion(expected("GraphicalMatrix"), on);
        assertTrue(result.valid());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED, result.recordStatus());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.DISABLED,
            repository(2000).checkAndRecordMfaCompletion(expected("GraphicalMatrix"),
                new GraphicalMatrixMfaSuccessRecordOptions(false, true, 1)).recordStatus());
        sql("ALTER TABLE graphicalmatrix_enrollment DROP COLUMN state_version");
        assertThrows(Exception.class, () -> repository(2000).checkAndRecordMfaCompletion(expected("GraphicalMatrix"), on));
    }

    @Test void totpActivationKeepsTimestampButRetainsRegistrationVersionTransition() throws Exception {
        final var repository = repository(30000L);
        final var start = repository.beginTotpRegistration("alice", 7, 30000L, 180000L);
        assertEquals(GraphicalMatrixTotpEnrollmentResult.Status.STARTED, start.getStatus());
        final var generator = GraphicalMatrixTotpSupport.class.getDeclaredMethod("generateCode", byte[].class, long.class);
        generator.setAccessible(true);
        final String code = (String) generator.invoke(null, Base32Support.decode(start.getSeed()), 1L);
        final var activated = repository.verifyTotpRegistration(start.getBinding(), code, 30000L);
        assertEquals(GraphicalMatrixTotpEnrollmentResult.Status.ACTIVATED, activated.getStatus());
        assertEquals("0", row().get("last_success_at"));
        assertEquals("9", row().get("state_version"));
        assertEquals("ACTIVE", row().get("totp_status"));
        assertEquals("30000", row().get("totp_registered_at"));
    }

    private void dbUser(final String user, final String password) throws Exception {
        Files.writeString(home.resolve("conf/graphicalmatrix/db.properties"), "graphicalmatrix.db.driver=org.h2.Driver\n"
            + "graphicalmatrix.db.url=" + ("sa".equals(user) ? url : url.replace(";DB_CLOSE_DELAY=-1", ""))
            + "\ngraphicalmatrix.db.user=" + user + "\ngraphicalmatrix.db.password="
            + password + "\ngraphicalmatrix.db.pool.enabled=false\ngraphicalmatrix.db.autoInit=false\n");
    }

    private void options(final boolean record, final boolean audit) throws Exception {
        Files.writeString(home.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.sequence.storage=plaintext\ngraphicalmatrix.mfa.successRecord.enabled=" + record
                + "\ngraphicalmatrix.mfa.completionAudit.enabled=" + audit + "\n");
    }

    private GraphicalMatrixRepository repository(final long now) {
        return new GraphicalMatrixRepository(home.toString(), Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }

    private GraphicalMatrixMfaSuccessRecord.Expectation expected(final String method) {
        return new GraphicalMatrixMfaSuccessRecord.Expectation("alice", method.toUpperCase(Locale.ROOT), flow(method), 7);
    }

    private static String flow(final String method) {
        return "GraphicalMatrix".equals(method) ? "authn/External" : "authn/" + method;
    }

    private ProfileRequestContext context(final String method) {
        final var prc = new ProfileRequestContext();
        prc.setProfileId(GraphicalMatrixSelfServiceAuthentication.PROFILE_ID);
        final var subject = new SubjectContext();
        subject.setPrincipalName("alice");
        prc.addSubcontext(subject);
        final var authn = new AuthenticationContext();
        authn.setForceAuthn(true);
        final var mfaResult = new AuthenticationResult("authn/MFA", new Subject());
        mfaResult.setAuthenticationInstant(Instant.now());
        authn.setAuthenticationResult(mfaResult);
        final var mfa = new MultiFactorAuthenticationContext();
        authn.addSubcontext(mfa);
        prc.addSubcontext(authn);
        for (final String id : List.of("authn/Password", flow(method))) {
            if ("authn/WebAuthn".equals(id)) {
                mfa.getActiveResults().put(id, OfficialWebAuthnTestSupport.result(prc, "alice", true));
                continue;
            }
            final Subject identity;
            if ("authn/TOTP".equals(id)) {
                identity = new OfficialTotpValidator().subjectFor(prc, "alice");
            } else {
                identity = new Subject();
                identity.getPrincipals().add(new UsernamePrincipal("alice"));
            }
            final var result = new AuthenticationResult(id, identity);
            result.setAuthenticationInstant(Instant.now());
            mfa.getActiveResults().put(id, result);
        }
        mfa.addSubcontext(new GraphicalMatrixMfaGuardContext("alice", flow(method), method.toUpperCase(Locale.ROOT), 7));
        return prc;
    }

    private Connection connection() throws Exception { return DriverManager.getConnection(url, "sa", ""); }
    private void sql(final String sql) throws Exception {
        try (Connection c = connection(); Statement statement = c.createStatement()) { statement.execute(sql); }
    }
    private Map<String, String> row() throws Exception {
        try (Connection c = connection(); Statement statement = c.createStatement();
                var rs = statement.executeQuery("SELECT * FROM graphicalmatrix_enrollment WHERE user_id='alice'")) {
            assertTrue(rs.next());
            final Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.put(rs.getMetaData().getColumnLabel(i), rs.getString(i));
            }
            return row;
        }
    }
}
