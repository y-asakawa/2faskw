/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.BasicControl;
import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixMfaLdapCompletionTest {
    @TempDir Path home;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);
    private static final GraphicalMatrixMfaSuccessRecordOptions ON = new GraphicalMatrixMfaSuccessRecordOptions(true, true, 1);

    @Test void allMethodsUseCriticalAssertionWithoutAdvancingVersion() throws Exception {
        for (final String method : List.of("GraphicalMatrix", "TOTP", "WebAuthn")) {
            final Server server = new Server();
            server.attributes.put("ldap_mfa_method", method);
            final var result = run(server, method, ON);
            assertTrue(result.valid());
            assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.UPDATED, result.recordStatus());
            assertEquals("7", server.attributes.get("ldap_state_version").get());
            assertEquals("2000", server.attributes.get("ldap_last_success_at").get());
            assertEquals("2000", server.attributes.get("ldap_updated_at").get());
            assertEquals(1, server.reads);
            assertEquals(1, server.writes);
            assertEquals(1, server.controls.length);
            assertEquals("test-original-control", server.controls[0].getID());
            assertTrue(server.absenceAssertion);
        }
    }

    @Test void rejectsObservedAdministrativeResetAndRetainsAConcurrentNewerTimestamp() throws Exception {
        final Server reset = new Server();
        reset.conflict = "reset";
        final var rejected = run(reset, "TOTP", ON);
        assertFalse(rejected.valid());
        assertEquals(2, reset.reads);
        assertEquals(1, reset.writes);
        final Server newer = new Server();
        newer.conflict = "timestamp";
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.UNCHANGED, run(newer, "TOTP", ON).recordStatus());
        assertEquals("3000", newer.attributes.get("ldap_last_success_at").get());
    }

    @Test void permissionAndUnsupportedControlFailuresNeverFallBackToUnconditionalModify() throws Exception {
        final Server server = new Server();
        server.failModify = true;
        final var result = run(server, "TOTP", ON);
        assertTrue(result.valid());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED, result.recordStatus());
        assertEquals(1, server.writes);
        assertNull(server.attributes.get("ldap_last_success_at"));
        assertEquals(1, server.controls.length);
    }

    @Test void malformedMultiValueTimestampDoesNotDenyAndOffDoesNotParseOrModifyIt() throws Exception {
        final Server server = new Server();
        final BasicAttribute invalid = new BasicAttribute("ldap_last_success_at", "1");
        invalid.add("2");
        server.attributes.put(invalid);
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED, run(server, "TOTP", ON).recordStatus());
        assertEquals(0, server.writes);
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.DISABLED,
            run(server, "TOTP", new GraphicalMatrixMfaSuccessRecordOptions(false, true, 1)).recordStatus());
        assertEquals(0, server.writes);
    }

    @Test void closeFailureIsOptionalButInitialReadFailureIsMandatory() throws Exception {
        final Server close = new Server();
        close.failClose = true;
        final var result = run(close, "TOTP", ON);
        assertTrue(result.valid());
        assertEquals("ldap_close_failed", result.failureCode());
        final Server read = new Server();
        read.failRead = true;
        assertThrows(NamingException.class, () -> run(read, "TOTP", ON));
        assertEquals(0, read.writes);
    }

    private GraphicalMatrixMfaSuccessRecord.Result run(final Server server, final String method,
            final GraphicalMatrixMfaSuccessRecordOptions options) throws Exception {
        Files.createDirectories(home.resolve("conf/graphicalmatrix"));
        Files.writeString(home.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.sequence.storage=plaintext\ngraphicalmatrix.ldap.url=ldaps://directory.example.test\n"
            + "graphicalmatrix.ldap.baseDN=ou=people,dc=example,dc=test\n"
            + "graphicalmatrix.ldap.bindDN=cn=test-only\ngraphicalmatrix.ldap.bindCredential=test-only\n");
        final var store = new GraphicalMatrixLdapEnrollmentStore(home.toString(),
            GraphicalMatrixSequenceStorage.load(home.toString()), GraphicalMatrixTotpSeedStorage.load(home.toString()),
            () -> (LdapContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { LdapContext.class }, server));
        return store.checkAndRecordMfaCompletion(new GraphicalMatrixMfaSuccessRecord.Expectation("alice",
            method.toUpperCase(Locale.ROOT), "GraphicalMatrix".equals(method) ? "authn/External" : "authn/" + method, 7), options, CLOCK);
    }

    private static final class Server implements InvocationHandler {
        final Attributes attributes = new BasicAttributes(true);
        Control[] controls = { new BasicControl("test-original-control") };
        int reads;
        int writes;
        String conflict;
        boolean failRead;
        boolean failModify;
        boolean failClose;
        boolean absenceAssertion;

        Server() {
            attributes.put("ldap_status", "ACTIVE");
            attributes.put("ldap_mfa_method", "TOTP");
            attributes.put("ldap_totp_status", "ACTIVE");
            attributes.put("ldap_totp_seed", "test-only-seed");
            attributes.put("ldap_sequence", "g1");
            attributes.put("ldap_state_version", "7");
        }

        @Override public Object invoke(final Object proxy, final java.lang.reflect.Method method, final Object[] args)
                throws Throwable {
            return switch (method.getName()) {
                case "getRequestControls" -> controls;
                case "setRequestControls" -> { controls = (Control[]) args[0]; yield null; }
                case "search" -> {
                    reads++;
                    if (failRead) { throw new NamingException("test-only lookup failure"); }
                    final SearchResult result = new SearchResult("cn=alice", null, (Attributes) attributes.clone());
                    result.setNameInNamespace("cn=alice,ou=people,dc=example,dc=test");
                    yield new NamingEnumeration<SearchResult>() {
                        private boolean available = true;
                        public boolean hasMore() { return available; }
                        public SearchResult next() { available = false; return result; }
                        public void close() { }
                        public boolean hasMoreElements() { return hasMore(); }
                        public SearchResult nextElement() { return next(); }
                    };
                }
                case "modifyAttributes" -> {
                    writes++;
                    assertEquals(2, controls.length);
                    final Control assertion = controls[1];
                    assertEquals("1.3.6.1.1.12", assertion.getID());
                    assertTrue(assertion.isCritical());
                    final byte[] bytes = assertion.getEncodedValue();
                    final String printable = new String(bytes, StandardCharsets.ISO_8859_1);
                    assertTrue(printable.contains("ldap_state_version"));
                    absenceAssertion = printable.contains("ldap_last_success_at") && printable.indexOf((char) 0xa2) >= 0;
                    if (failModify) { throw new NamingException("LDAP error code 12 unsupported critical extension"); }
                    if (conflict != null) {
                        if ("reset".equals(conflict)) { attributes.put("ldap_state_version", "8"); }
                        else { attributes.put("ldap_last_success_at", "3000"); }
                        throw new NamingException("LDAP error code 122 assertion failed");
                    }
                    for (final ModificationItem update : (ModificationItem[]) args[1]) {
                        assertTrue(List.of("ldap_last_success_at", "ldap_updated_at").contains(update.getAttribute().getID()));
                        attributes.put(update.getAttribute());
                    }
                    yield null;
                }
                case "close" -> {
                    if (failClose) { throw new NamingException("test-only close failure"); }
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
