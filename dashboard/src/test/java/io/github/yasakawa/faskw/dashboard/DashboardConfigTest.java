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
package io.github.yasakawa.faskw.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardConfigTest {

    @TempDir
    Path temporary;

    @Test
    void proxyAuthenticationRequiresAStrongReadableSecret() throws Exception {
        final Path missing = temporary.resolve("proxy-missing.properties");
        Files.writeString(missing, """
                dashboard.enabled=false
                dashboard.http.bindAddress=127.0.0.1
                dashboard.auth.mode=proxy
                dashboard.auth.proxySecretFile=%s
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(temporary.resolve("missing.secret"),
                    temporary.resolve("proxy-missing")));
        assertThrows(IllegalArgumentException.class, () -> DashboardConfig.load(missing));

        final Path weakSecret = Files.writeString(temporary.resolve("weak.secret"), "too-short");
        final Path weak = temporary.resolve("proxy-weak.properties");
        Files.writeString(weak, Files.readString(missing).replace(
            temporary.resolve("missing.secret").toString(), weakSecret.toString()));
        assertThrows(IllegalArgumentException.class, () -> DashboardConfig.load(weak));

        final Path strongSecret = Files.writeString(temporary.resolve("strong.secret"),
            "strong-proxy-secret-12345678901234567890\n");
        final Path valid = temporary.resolve("proxy-valid.properties");
        Files.writeString(valid, Files.readString(missing).replace(
            temporary.resolve("missing.secret").toString(), strongSecret.toString()));
        assertEquals(strongSecret, DashboardConfig.load(valid).proxySecretFile());
    }

    @Test
    void permitsNoneAuthenticationOnlyOnLoopback() throws Exception {
        final Path valid = temporary.resolve("valid.properties");
        Files.writeString(valid, """
                dashboard.enabled=false
                dashboard.http.bindAddress=127.0.0.1
                dashboard.auth.mode=none
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(temporary.resolve("dashboard")));
        assertEquals("none", DashboardConfig.load(valid).authMode());

        final Path invalid = temporary.resolve("invalid.properties");
        Files.writeString(invalid, """
                dashboard.enabled=false
                dashboard.http.bindAddress=0.0.0.0
                dashboard.auth.mode=none
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(temporary.resolve("dashboard")));
        assertThrows(IllegalArgumentException.class, () -> DashboardConfig.load(invalid));
    }

    @Test
    void permitsLocalAuthenticationWithExplicitNetworksAndLimitedRole() throws Exception {
        final Path configuration = temporary.resolve("local.properties");
        Files.writeString(configuration, """
                dashboard.enabled=false
                dashboard.http.bindAddress=192.0.2.10
                dashboard.auth.mode=local
                dashboard.auth.localAllowedCIDRs=192.0.2.0/24,2001:db8::/32
                dashboard.auth.localRole=dashboard_operator
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(temporary.resolve("local-dashboard")));

        final DashboardConfig config = DashboardConfig.load(configuration);
        assertEquals("local", config.authMode());
        assertEquals("192.0.2.0/24,2001:db8::/32", config.localAllowedCidrs());
        assertEquals("DASHBOARD_OPERATOR", config.localRole());
    }

    @Test
    void appliesAndValidatesRetentionAndQueryRangeLimits() throws Exception {
        final Path defaults = temporary.resolve("retention-defaults.properties");
        Files.writeString(defaults, """
                dashboard.enabled=false
                dashboard.http.bindAddress=127.0.0.1
                dashboard.auth.mode=none
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(temporary.resolve("retention-defaults")));
        final DashboardConfig defaultConfig = DashboardConfig.load(defaults);
        assertEquals(30, defaultConfig.retentionDays());
        assertEquals(90, defaultConfig.aggregateRetentionDays());
        assertEquals(100, defaultConfig.maxRangeDays());

        final Path extended = temporary.resolve("retention-extended.properties");
        Files.writeString(extended, """
                dashboard.enabled=false
                dashboard.http.bindAddress=127.0.0.1
                dashboard.auth.mode=none
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                dashboard.storage.retentionDays=90
                dashboard.storage.aggregateRetentionDays=180
                dashboard.query.maxRangeDays=100
                """.formatted(temporary.resolve("retention-extended")));
        final DashboardConfig extendedConfig = DashboardConfig.load(extended);
        assertEquals(90, extendedConfig.retentionDays());
        assertEquals(180, extendedConfig.aggregateRetentionDays());
        assertEquals(100, extendedConfig.maxRangeDays());

        final String[] invalidSettings = {
            "dashboard.storage.retentionDays=91",
            "dashboard.storage.aggregateRetentionDays=181",
            "dashboard.query.maxRangeDays=101",
            """
            dashboard.storage.retentionDays=90
            dashboard.storage.aggregateRetentionDays=30
            """
        };
        for (int i = 0; i < invalidSettings.length; i++) {
            final Path invalid = temporary.resolve("retention-invalid-" + i + ".properties");
            Files.writeString(invalid, """
                    dashboard.enabled=false
                    dashboard.http.bindAddress=127.0.0.1
                    dashboard.auth.mode=none
                    dashboard.ingest.enabled=false
                    dashboard.storage.path=%s
                    %s
                    """.formatted(
                            temporary.resolve("retention-invalid-" + i),
                            invalidSettings[i]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DashboardConfig.load(invalid));
        }
    }

    @Test
    void rejectsUnsafeLocalAuthenticationSettings() throws Exception {
        final String[] unsafeSettings = {
            """
            dashboard.http.bindAddress=0.0.0.0
            dashboard.auth.localAllowedCIDRs=192.0.2.0/24
            dashboard.auth.localRole=DASHBOARD_VIEWER
            """,
            """
            dashboard.http.bindAddress=192.0.2.10
            dashboard.auth.localAllowedCIDRs=
            dashboard.auth.localRole=DASHBOARD_VIEWER
            """,
            """
            dashboard.http.bindAddress=192.0.2.10
            dashboard.auth.localAllowedCIDRs=0.0.0.0/0
            dashboard.auth.localRole=DASHBOARD_VIEWER
            """,
            """
            dashboard.http.bindAddress=192.0.2.10
            dashboard.auth.localAllowedCIDRs=localhost/32
            dashboard.auth.localRole=DASHBOARD_VIEWER
            """,
            """
            dashboard.http.bindAddress=192.0.2.10
            dashboard.auth.localAllowedCIDRs=192.0.2.0/24
            dashboard.auth.localRole=DASHBOARD_AUDITOR
            """
        };

        for (int i = 0; i < unsafeSettings.length; i++) {
            final Path configuration = temporary.resolve("unsafe-local-" + i + ".properties");
            Files.writeString(configuration, """
                    dashboard.enabled=false
                    dashboard.auth.mode=local
                    dashboard.ingest.enabled=false
                    dashboard.storage.path=%s
                    %s
                    """.formatted(temporary.resolve("unsafe-" + i), unsafeSettings[i]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DashboardConfig.load(configuration));
        }
    }

    @Test
    void offlineImportCanLoadStorageBeforeMtlsFilesAreProvisioned() throws Exception {
        final Path configuration = temporary.resolve("offline.properties");
        Files.writeString(configuration, """
                dashboard.enabled=false
                dashboard.http.bindAddress=127.0.0.1
                dashboard.auth.mode=none
                dashboard.ingest.enabled=true
                dashboard.ingest.requireMtls=true
                dashboard.storage.path=%s
                """.formatted(temporary.resolve("offline-db")));

        assertThrows(IllegalArgumentException.class, () -> DashboardConfig.load(configuration));
        assertEquals(
                temporary.resolve("offline-db"),
                DashboardConfig.loadForOffline(configuration).storagePath());
    }
}
