/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixInitialSequenceSecurityTest {
    @TempDir Path home;
    String previousHome;
    String url;
    GraphicalMatrixConfig config;
    GraphicalMatrixSequenceStorage storage;

    @BeforeEach void configure() throws Exception {
        final Field initialized = GraphicalMatrixAdminApiServlet.class.getDeclaredField("SCHEMA_INITIALIZED");
        initialized.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) initialized.get(null)).set(false);
        previousHome = System.getProperty("idp.home");
        System.setProperty("idp.home", home.toString());
        url = "jdbc:h2:mem:initial-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        final Path conf = home.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("db.properties"), "graphicalmatrix.db.driver=org.h2.Driver\ngraphicalmatrix.db.url=" + url
            + "\ngraphicalmatrix.db.user=sa\ngraphicalmatrix.db.password=\ngraphicalmatrix.db.autoInit=true\ngraphicalmatrix.db.pool.enabled=false\n");
        Files.writeString(conf.resolve("graphicalmatrix.properties"), "graphicalmatrix.sequence.storage=hash\ngraphicalmatrix.sequence.pepper=test-only-pepper\ngraphicalmatrix.graphicals=img01-25\ngraphicalmatrix.choice=4\ngraphicalmatrix.selfservice.enabled=true\n");
        config = GraphicalMatrixConfig.load(home.toString());
        storage = GraphicalMatrixSequenceStorage.load(home.toString());
    }

    @AfterEach void restore() {
        if (previousHome == null) { System.clearProperty("idp.home"); }
        else { System.setProperty("idp.home", previousHome); }
    }

    Object upsert(final String user, final String body) throws Exception {
        final Method method = GraphicalMatrixAdminApiServlet.class.getDeclaredMethod("upsertUser", String.class,
            GraphicalMatrixAdminApiServlet.JsonBody.class, GraphicalMatrixConfig.class);
        method.setAccessible(true);
        return method.invoke(null, user, new GraphicalMatrixAdminApiServlet.JsonBody(body), config);
    }

    String field(final String user, final String name) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
                PreparedStatement ps = c.prepareStatement("SELECT " + name + " FROM graphicalmatrix_enrollment WHERE user_id=?")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); }
        }
    }

    @Test void currentOnlyDoesNotCopySecretAndInitialOnlyUpdatePreservesCurrent() throws Exception {
        upsert("alice", "{\"sequence\":\"img01,img02,img03,img04\"}");
        assertEquals("", field("alice", "initial_sequence"));
        final String current = field("alice", "sequence");
        assertTrue(current.startsWith("hsp1:"));
        upsert("alice", "{\"initialSequence\":\"img05,img06,img07,img08\"}");
        assertEquals(current, field("alice", "sequence"));
        assertEquals("img05,img06,img07,img08", field("alice", "initial_sequence"));
    }

    @Test void bootstrapRequiresChangeAndProtectedResetRestoresInitial() throws Exception {
        upsert("alice", "{\"initialSequence\":\"img01,img02,img03,img04\"}");
        assertEquals("1", field("alice", "force_sequence_change"));
        upsert("alice", "{\"sequence\":\"img05,img06,img07,img08\"}");
        final String initial = field("alice", "initial_sequence");
        final Method reset = GraphicalMatrixAdminApiServlet.class.getDeclaredMethod("resetUser", String.class, GraphicalMatrixConfig.class);
        reset.setAccessible(true);
        assertEquals(true, reset.invoke(null, "alice", config));
        assertEquals("img01,img02,img03,img04", initial);
        assertTrue(storage.matches(field("alice", "sequence"), List.of("img01", "img02", "img03", "img04"), true, false));
        assertThrows(InvocationTargetException.class,
            () -> upsert("bob", "{\"initialSequence\":\"img01,img02,img03,img04\",\"forceSequenceChange\":false}"));
        assertThrows(IllegalArgumentException.class, () -> storage.validateStored("hsp1:4:bad:bad", config));
    }

    @Test void resetInvalidatesOldHttpHandoffButFreshHandoffWorks() throws Exception {
        upsert("alice", "{\"initialSequence\":\"img01,img02,img03,img04\"}");
        final var attributes = new java.util.HashMap<String,Object>();
        final var session = (jakarta.servlet.http.HttpSession) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {jakarta.servlet.http.HttpSession.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAttribute" -> attributes.get(args[0]);
                case "setAttribute" -> { attributes.put((String) args[0], args[1]); yield null; }
                case "removeAttribute" -> { attributes.remove(args[0]); yield null; }
                default -> null;
            });
        final var request = (jakarta.servlet.http.HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getSession" -> session;
                case "getContextPath" -> "/idp";
                case "getRemoteAddr" -> "127.0.0.1";
                default -> null;
            });
        final int[] status = {200};
        final var response = (jakarta.servlet.http.HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {jakarta.servlet.http.HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getWriter" -> new java.io.PrintWriter(new java.io.StringWriter());
                case "setStatus" -> { status[0] = (Integer) args[0]; yield null; }
                case "encodeURL", "encodeRedirectURL" -> args[0];
                default -> null;
            });
        final Method start = GraphicalMatrixChangeServlet.class.getDeclaredMethod("startFromSelfService",
            jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class, GraphicalMatrixConfig.class);
        start.setAccessible(true);
        GraphicalMatrixSelfServiceSession.initialize(session, "alice", 0L, System.currentTimeMillis() + 60000L);
        final Method reset = GraphicalMatrixAdminApiServlet.class.getDeclaredMethod("resetUser", String.class, GraphicalMatrixConfig.class);
        reset.setAccessible(true);
        assertEquals(true, reset.invoke(null, "alice", config));
        start.invoke(null, request, response, config);
        assertEquals(403, status[0]);
        assertNull(attributes.get("graphicalmatrixChange.verified"));
        assertEquals("1", field("alice", "state_version"));
        status[0] = 200;
        GraphicalMatrixSelfServiceSession.initialize(session, "alice", 1L, System.currentTimeMillis() + 60000L);
        start.invoke(null, request, response, config);
        assertEquals(200, status[0]);
        assertEquals(Boolean.TRUE, attributes.get("graphicalmatrixChange.verified"));
        assertEquals(1L, attributes.get("graphicalmatrixChange.stateVersion"));
    }

    @Test void nativeReceiptSurvivesMfaMergeForTotpWebAuthnAndGraphicalMatrix() throws Exception {
        for (final String method : List.of("TOTP", "WebAuthn", "GraphicalMatrix")) {
            final String user = "alice-" + method;
            upsert(user, "{\"sequence\":\"img01,img02,img03,img04\",\"mfaMethod\":\"" + method + "\"}");
            try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE graphicalmatrix_enrollment SET totp_seed='test-only-seed', totp_status='ACTIVE'");
            }
            final String flow = "TOTP".equals(method) ? "authn/TOTP" : "WebAuthn".equals(method) ? "authn/WebAuthn" : "authn/External";
            final var context = new org.opensaml.profile.context.ProfileRequestContext();
            context.setProfileId(GraphicalMatrixSelfServiceAuthentication.PROFILE_ID);
            final var subject = new net.shibboleth.idp.authn.context.SubjectContext();
            subject.setPrincipalName(user); context.addSubcontext(subject);
            final var authn = new net.shibboleth.idp.authn.context.AuthenticationContext();
            authn.setForceAuthn(true); context.addSubcontext(authn);
            final var mfa = new net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext();
            authn.addSubcontext(mfa);
            final var merged = new javax.security.auth.Subject();
            for (final String component : List.of("authn/Password", flow)) {
                final var identity = new javax.security.auth.Subject();
                identity.getPrincipals().add(new net.shibboleth.idp.authn.principal.UsernamePrincipal(user));
                final var result = new net.shibboleth.idp.authn.AuthenticationResult(component, identity);
                result.setAuthenticationInstant(java.time.Instant.now());
                mfa.getActiveResults().put(component, result);
                merged.getPrincipals().add(new net.shibboleth.idp.authn.principal.AuthenticationResultPrincipal(result));
            }
            mfa.addSubcontext(new GraphicalMatrixMfaGuardContext(user, flow, method.toUpperCase(java.util.Locale.ROOT), 0L));
            new GraphicalMatrixMfaCompletionStrategy().apply(context);
            assertNotNull(context.getSubcontext(GraphicalMatrixSelfServiceAuthorizationContext.class));
            authn.removeSubcontext(net.shibboleth.idp.authn.context.MultiFactorAuthenticationContext.class);
            final var combined = new net.shibboleth.idp.authn.AuthenticationResult("authn/MFA", merged);
            combined.setAuthenticationInstant(java.time.Instant.now()); authn.setAuthenticationResult(combined);
            assertTrue(GraphicalMatrixSelfServiceAuthentication.validate(context).isValid());
        }
    }

    @Test void explicitReplacementCanRepairMalformedLegacyInitial() throws Exception {
        upsert("alice", "{\"sequence\":\"img01,img02,img03,img04\"}");
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE graphicalmatrix_enrollment SET initial_sequence='hsp1:4:bad:bad'");
        }
        upsert("alice", "{\"initialSequence\":\"img05,img06,img07,img08\"}");
        assertEquals("img05,img06,img07,img08", field("alice", "initial_sequence"));
    }

    @Test void readableInitialAndProtectedResetWorkInAllCurrentStorageModes() throws Exception {
        for (final String mode : List.of("hash", "keyword", "aes-gcm")) {
            Files.writeString(home.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
                "graphicalmatrix.graphicals=img01-25\ngraphicalmatrix.choice=4\ngraphicalmatrix.sequence.storage=" + mode
                + "\ngraphicalmatrix.sequence.pepper=test-only-pepper\ngraphicalmatrix.sequence.keyword=test-only-keyword\ngraphicalmatrix.sequence.aesKey=0123456789abcdef\n");
            final GraphicalMatrixConfig cfg = GraphicalMatrixConfig.load(home.toString());
            final GraphicalMatrixSequenceStorage store = GraphicalMatrixSequenceStorage.load(home.toString());
            final String initial = store.normalizeInitial("img01,img02,img03,img04", cfg);
            assertEquals("img01,img02,img03,img04", initial);
            assertEquals(initial, store.normalizeInitial(initial, cfg));
            final String reset = store.resetFromInitial(initial, cfg);
            assertNotEquals(initial, reset);
            assertTrue(store.matches(reset, List.of("img01", "img02", "img03", "img04"), true, false));
            assertThrows(RuntimeException.class, () -> store.normalizeInitial("hsp1:4:bad:bad", cfg));
        }
    }

    @Test void apiOutputShowsReadableInitialAndKeepsHashCurrentHiddenWhenEnabled() throws Exception {
        Files.writeString(home.resolve("conf/graphicalmatrix/api.properties"), "graphicalmatrix.api.response.excludeSequences=false\n");
        Object row = upsert("alice", "{\"sequence\":\"img01,img02,img03,img04\"}");
        final Method json = row.getClass().getDeclaredMethod("toJson");
        json.setAccessible(true);
        assertTrue(((String) json.invoke(row)).contains("\"initialSequence\":[]"));
        row = upsert("alice", "{\"initialSequence\":\"img05,img06,img07,img08\"}");
        assertTrue(((String) json.invoke(row)).contains("\"initialSequence\":[\"img05\",\"img06\",\"img07\",\"img08\"]"));
        assertTrue(((String) json.invoke(row)).contains("\"sequence\":[]"));
    }

    @Test void legacyHashedInitialDoesNotBlockCurrentUpdatesAndRequiresExplicitReentry() throws Exception {
        upsert("alice", "{\"sequence\":\"img01,img02,img03,img04\"}");
        final String legacy = storage.encode(List.of("img09", "img10", "img11", "img12"), true, false);
        try (Connection c = DriverManager.getConnection(url, "sa", ""); PreparedStatement ps = c.prepareStatement("UPDATE graphicalmatrix_enrollment SET initial_sequence=? WHERE user_id='alice'")) {
            ps.setString(1, legacy); ps.executeUpdate();
        }
        upsert("alice", "{\"sequence\":\"img05,img06,img07,img08\"}");
        assertEquals(legacy, field("alice", "initial_sequence"));
        final Object row = upsert("alice", "{\"status\":\"ACTIVE\"}");
        final Method json = row.getClass().getDeclaredMethod("toJson"); json.setAccessible(true);
        assertTrue(((String) json.invoke(row)).contains("\"initialSequenceRecoverable\":false"));
        assertTrue(((String) json.invoke(row)).contains("\"initialSequenceStorage\":\"hash\""));
        final String current = field("alice", "sequence");
        final Method reset = GraphicalMatrixAdminApiServlet.class.getDeclaredMethod("resetUser", String.class, GraphicalMatrixConfig.class);
        reset.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> reset.invoke(null, "alice", config));
        assertEquals(current, field("alice", "sequence"));
        final Method run = GraphicalMatrixSequenceMigrationTool.class.getDeclaredMethod("run", String.class, boolean.class);
        run.setAccessible(true);
        assertEquals(1, run.invoke(null, home.toString(), true));
        assertEquals(current, field("alice", "sequence"));
        upsert("alice", "{\"initialSequence\":\"img09,img10,img11,img12\"}");
        assertEquals("img09,img10,img11,img12", field("alice", "initial_sequence"));
        assertEquals(current, field("alice", "sequence"));
    }

    @Test void metadataOnlyUpdateCannotDisableBootstrapChangeRequirement() throws Exception {
        upsert("alice", "{\"initialSequence\":\"img01,img02,img03,img04\"}");
        assertThrows(InvocationTargetException.class, () -> upsert("alice", "{\"forceSequenceChange\":false}"));
        assertEquals("1", field("alice", "force_sequence_change"));
    }

    @Test void legacyEncryptedInitialCanBeRestoredWithItsOriginalKeyWhileCurrentModeIsHash() throws Exception {
        for (final String oldMode : List.of("keyword", "aes-gcm")) {
            final Path properties = home.resolve("conf/graphicalmatrix/graphicalmatrix.properties");
            final String common = "graphicalmatrix.graphicals=img01-25\ngraphicalmatrix.choice=4\ngraphicalmatrix.sequence.pepper=test-only-pepper\ngraphicalmatrix.sequence.keyword=test-only-keyword\ngraphicalmatrix.sequence.aesKey=0123456789abcdef\n";
            Files.writeString(properties, common + "graphicalmatrix.sequence.storage=" + oldMode + "\n");
            final String legacy = GraphicalMatrixSequenceStorage.load(home.toString()).encode(List.of("img01", "img02", "img03", "img04"), true, false);
            Files.writeString(properties, common + "graphicalmatrix.sequence.storage=hash\n");
            final GraphicalMatrixSequenceStorage target = GraphicalMatrixSequenceStorage.load(home.toString());
            final GraphicalMatrixConfig cfg = GraphicalMatrixConfig.load(home.toString());
            assertEquals("img01,img02,img03,img04", target.normalizeInitial(legacy, cfg));
            final String reset = target.resetFromInitial(legacy, cfg);
            assertTrue(reset.startsWith("hsp1:"));
            assertTrue(target.matches(reset, List.of("img01", "img02", "img03", "img04"), true, false));
        }
    }

    @Test void migrationIsIdempotentAndPreservesTotpSeedAndMethod() throws Exception {
        upsert("alice", "{\"sequence\":\"img01,img02,img03,img04\"}");
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE graphicalmatrix_enrollment SET initial_sequence='img01,img02,img03,img04', mfa_method='TOTP', totp_seed='test-only-seed', totp_status='ACTIVE' WHERE user_id='alice'");
        }
        final Method run = GraphicalMatrixSequenceMigrationTool.class.getDeclaredMethod("run", String.class, boolean.class);
        run.setAccessible(true);
        assertEquals(0, run.invoke(null, home.toString(), true));
        final String initial = field("alice", "initial_sequence");
        final String version = field("alice", "state_version");
        assertEquals("img01,img02,img03,img04", initial);
        assertEquals("TOTP", field("alice", "mfa_method"));
        assertEquals("test-only-seed", field("alice", "totp_seed"));
        assertEquals(0, run.invoke(null, home.toString(), true));
        assertEquals(initial, field("alice", "initial_sequence"));
        assertEquals(version, field("alice", "state_version"));
    }
}
