package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.opensaml.storage.EnumeratableStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixLdapStorageConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void loadsSubtreeStorageSettingsAndFallsBackToLdapConnection() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        final Path credentials = idpHome.resolve("credentials");
        Files.createDirectories(conf);
        Files.createDirectories(credentials);
        final Path secret = credentials.resolve("ldap.secret");
        Files.writeString(secret, "bind-secret\n");
        Files.writeString(conf.resolve("ldap.properties"),
            "graphicalmatrix.ldap.url = ldaps://127.0.0.1:686\n"
            + "graphicalmatrix.ldap.bindDN = CN=writer,OU=system,DC=example,DC=jp\n"
            + "graphicalmatrix.ldap.bindCredentialFile = " + secret + "\n");
        Files.writeString(conf.resolve("webauthn-ldap.properties"),
            "graphicalmatrix.webauthn.ldap.layout = subtree\n"
            + "graphicalmatrix.webauthn.ldap.baseDN = OU=WebAuthnStorage,DC=example,DC=jp\n"
            + "graphicalmatrix.webauthn.ldap.attr.context = ldap_webauthn_context\n"
            + "graphicalmatrix.webauthn.ldap.attr.id = ldap_webauthn_id\n");

        final GraphicalMatrixLdapStorageConfig config =
            GraphicalMatrixLdapStorageConfig.load(idpHome.toString());

        assertEquals(GraphicalMatrixLdapStorageConfig.LAYOUT_SUBTREE, config.layout());
        assertEquals("ldaps://127.0.0.1:686", config.url());
        assertEquals("CN=writer,OU=system,DC=example,DC=jp", config.bindDn());
        assertEquals("bind-secret", config.bindCredential());
        assertEquals("OU=WebAuthnStorage,DC=example,DC=jp", config.baseDn());
        assertEquals("ldap_webauthn_context", config.contextAttr());
        assertEquals("ldap_webauthn_id", config.idAttr());
    }

    @Test
    void loadsUserEntryLayout() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("webauthn-ldap.properties"),
            "graphicalmatrix.webauthn.ldap.url = ldap://127.0.0.1:389\n"
            + "graphicalmatrix.webauthn.ldap.bindDN = cn=Directory Manager\n"
            + "graphicalmatrix.webauthn.ldap.bindCredential = secret\n"
            + "graphicalmatrix.webauthn.ldap.layout = user-entry\n"
            + "graphicalmatrix.webauthn.ldap.userBaseDN = ou=People,dc=example,dc=test\n"
            + "graphicalmatrix.webauthn.ldap.userFilter = (uid={id})\n"
            + "graphicalmatrix.webauthn.ldap.userSubtreeSearch = false\n");

        final GraphicalMatrixLdapStorageConfig config =
            GraphicalMatrixLdapStorageConfig.load(idpHome.toString());

        assertEquals(GraphicalMatrixLdapStorageConfig.LAYOUT_USER_ENTRY, config.layout());
        assertEquals("ou=People,dc=example,dc=test", config.userBaseDn());
        assertEquals("(uid={id})", config.userFilter());
        assertEquals(false, config.userSubtreeSearch());
    }

    @Test
    void rejectsUnsupportedValueStorageMode() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("webauthn-ldap.properties"),
            "graphicalmatrix.webauthn.ldap.url = ldap://127.0.0.1:389\n"
            + "graphicalmatrix.webauthn.ldap.bindDN = cn=Directory Manager\n"
            + "graphicalmatrix.webauthn.ldap.bindCredential = secret\n"
            + "graphicalmatrix.webauthn.ldap.baseDN = ou=Storage,dc=example,dc=test\n"
            + "graphicalmatrix.webauthn.ldap.value.storage = aes-gcm\n");

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixLdapStorageConfig.load(idpHome.toString()));

        assertTrue(ex.getMessage().contains("only plaintext is implemented"));
    }

    @Test
    void derivesStableSubtreeRecordRdn() {
        final String first = GraphicalMatrixLdapSubtreeRecordStore.recordRdnValue(
            "net.shibboleth.idp.plugin.authn.webauthn", "test01");
        final String second = GraphicalMatrixLdapSubtreeRecordStore.recordRdnValue(
            "net.shibboleth.idp.plugin.authn.webauthn", "test01");
        final String different = GraphicalMatrixLdapSubtreeRecordStore.recordRdnValue(
            "other-context", "test01");

        assertEquals(first, second);
        assertNotEquals(first, different);
        assertTrue(first.startsWith("sr-"));
    }

    @Test
    void storageServiceIsEnumerableForWebAuthnPlugin() {
        assertTrue(new GraphicalMatrixLdapStorageService() instanceof EnumeratableStorageService);
    }
}
