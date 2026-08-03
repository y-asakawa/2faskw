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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

    @TempDir
    Path temporary;

    @Test
    void defaultsToPlainUserIds() throws Exception {
        final Path source = Files.writeString(temporary.resolve("audit.log"), "");
        final Path keyStore = Files.writeString(temporary.resolve("agent.p12"), "test");
        final Path keyStorePassword = Files.writeString(
                temporary.resolve("agent.password"), "test\n");
        final Path trustStore = Files.writeString(temporary.resolve("ca.p12"), "test");
        final Path trustStorePassword = Files.writeString(
                temporary.resolve("ca.password"), "test\n");
        final Path configuration = temporary.resolve("agent.properties");
        Files.writeString(configuration, """
                agent.enabled=true
                agent.nodeId=node-01
                agent.source=%s
                agent.dashboardUrl=https://dashboard.example.org:9443/2faskw-dashboard/ingest/v1/events
                agent.tls.keyStore=%s
                agent.tls.keyStorePasswordFile=%s
                agent.tls.trustStore=%s
                agent.tls.trustStorePasswordFile=%s
                """.formatted(
                        source,
                        keyStore,
                        keyStorePassword,
                        trustStore,
                        trustStorePassword));

        assertEquals(
                PrivacyFilter.UserMode.PLAIN,
                AgentConfig.load(configuration).userMode());
        assertEquals(
                PrivacyFilter.IpMode.PLAIN,
                AgentConfig.load(configuration).ipMode());
        assertEquals(60, AgentConfig.load(configuration).heartbeatSeconds());
    }
}
