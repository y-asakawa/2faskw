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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixLdapConfigTest {
    @TempDir
    Path idpHome;

    @Test
    void loadsLdapPropertiesAndCredentialFile() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        final Path credentials = idpHome.resolve("credentials");
        Files.createDirectories(conf);
        Files.createDirectories(credentials);
        final Path bindSecret = credentials.resolve("ldap-bind.secret");
        Files.writeString(bindSecret, "secret-value\n");
        Files.writeString(conf.resolve("graphicalmatrix.properties"),
            "graphicalmatrix.savedata = ldap\n");
        Files.writeString(conf.resolve("ldap.properties"),
            "graphicalmatrix.ldap.url = ldaps://127.0.0.1:686\n"
            + "graphicalmatrix.ldap.baseDN = OU=people,DC=example,DC=jp\n"
            + "graphicalmatrix.ldap.userFilter = (uid={user})\n"
            + "graphicalmatrix.ldap.bindDN = CN=writer,OU=system,DC=example,DC=jp\n"
            + "graphicalmatrix.ldap.bindCredentialFile = " + bindSecret + "\n"
            + "graphicalmatrix.ldap.attr.sequence = gmSequence\n");

        final GraphicalMatrixLdapConfig config = GraphicalMatrixLdapConfig.load(idpHome.toString());

        assertEquals("ldaps://127.0.0.1:686", config.url());
        assertEquals("OU=people,DC=example,DC=jp", config.baseDn());
        assertEquals("(uid={user})", config.userFilter());
        assertEquals("CN=writer,OU=system,DC=example,DC=jp", config.bindDn());
        assertEquals("secret-value", config.bindCredential());
        assertEquals("gmSequence", config.sequenceAttr());
    }

    @Test
    void rejectsMissingBindCredential() throws Exception {
        final Path conf = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("graphicalmatrix.properties"),
            "graphicalmatrix.savedata = ldap\n");
        Files.writeString(conf.resolve("ldap.properties"),
            "graphicalmatrix.ldap.url = ldaps://127.0.0.1:686\n"
            + "graphicalmatrix.ldap.baseDN = OU=people,DC=example,DC=jp\n"
            + "graphicalmatrix.ldap.userFilter = (uid={user})\n"
            + "graphicalmatrix.ldap.bindDN = CN=writer,OU=system,DC=example,DC=jp\n");

        assertThrows(IllegalArgumentException.class,
            () -> GraphicalMatrixLdapConfig.load(idpHome.toString()));
    }
}
