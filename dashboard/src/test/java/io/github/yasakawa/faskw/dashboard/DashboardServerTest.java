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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardServerTest {

    @TempDir
    Path temporary;

    @Test
    void requestRateLimiterEnforcesPerKeyLimitAndHardCapacity() {
        final DashboardServer.RequestRateLimiter limiter =
                new DashboardServer.RequestRateLimiter(2, 2);

        assertTrue(limiter.allow("user-a"));
        assertTrue(limiter.allow("user-a"));
        assertFalse(limiter.allow("user-a"));
        assertTrue(limiter.allow("user-b"));
        assertTrue(limiter.allow("user-c"));

        assertEquals(2, limiter.size());
        assertFalse(limiter.contains("user-a"));
        assertTrue(limiter.contains("user-b"));
        assertTrue(limiter.contains("user-c"));
    }

    @Test
    void servesReadOnlyUiAndSummaryWithSecurityHeaders() throws Exception {
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        final Path database = temporary.resolve("dashboard");
        final Instant now = Instant.now();
        try (DashboardStore store = new DashboardStore(database)) {
            store.ingest(List.of(
                    new NormalizedEvent(
                            1,
                            "a".repeat(64),
                            now,
                            now,
                            "node-01",
                            "VERIFY",
                            "OK",
                            "matched",
                            "User_001",
                            "192.0.2.25",
                            null,
                            "ok",
                            "1",
                            0,
                            "b".repeat(64)),
                    new NormalizedEvent(
                            1,
                            "c".repeat(64),
                            now,
                            now,
                            "node-01",
                            "VERIFY",
                            "FAIL",
                            "mismatch",
                            "user002",
                            "198.51.100.20",
                            null,
                            "ok",
                            "1",
                            0,
                            "d".repeat(64))), "test");
        }

        final Path configuration = temporary.resolve("dashboard.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.http.basePath=/2faskw-dashboard
                dashboard.auth.mode=none
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, database));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/2faskw-dashboard/"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("2FAS-KW"));
            assertTrue(page.body().contains("data-hours=\"720\">30日"));
            assertTrue(page.body().contains("data-hours=\"1440\">60日"));
            assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());

            final HttpResponse<String> summary = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, summary.statusCode());
            final JsonNode json = JsonSupport.MAPPER.readTree(summary.body());
            assertEquals(1, json.get("graphicalMatrixSuccess").asLong());
            assertEquals(3, json.get("topMismatchLimit").asInt());
            assertEquals(3, json.get("topSourceNetworkLimit").asInt());
            assertEquals(
                    "192.0.2.25",
                    json.get("topSourceNetworks").get(0).get("sourceNetwork").asText());
            assertEquals(
                    "198.51.100.20",
                    json.get("topSourceMismatchNetworks").get(0)
                            .get("sourceNetwork").asText());
            assertTrue(json.get("from").isTextual());
            assertTrue(json.get("to").isTextual());

            final Instant customFrom = now.minusSeconds(60);
            final Instant customTo = now.plusSeconds(60);
            final HttpResponse<String> customRangeSummary = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"
                                    + "?from=" + customFrom
                                    + "&to=" + customTo))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, customRangeSummary.statusCode());
            final JsonNode customRangeJson =
                    JsonSupport.MAPPER.readTree(customRangeSummary.body());
            assertEquals(customFrom.toString(), customRangeJson.get("from").asText());
            assertEquals(customTo.toString(), customRangeJson.get("to").asText());

            final HttpResponse<String> reversedRangeSummary = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"
                                    + "?from=" + customTo
                                    + "&to=" + customFrom))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, reversedRangeSummary.statusCode());

            final HttpResponse<String> limitedSummary = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"
                                    + "?topMismatchLimit=12"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, limitedSummary.statusCode());
            assertEquals(12, JsonSupport.MAPPER.readTree(
                    limitedSummary.body()).get("topMismatchLimit").asInt());

            final HttpResponse<String> limitedNetworkSummary = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"
                                    + "?topSourceNetworkLimit=12"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, limitedNetworkSummary.statusCode());
            assertEquals(12, JsonSupport.MAPPER.readTree(
                    limitedNetworkSummary.body()).get("topSourceNetworkLimit").asInt());

            final HttpResponse<String> invalidSummaryLimit = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"
                                    + "?topMismatchLimit=101"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidSummaryLimit.statusCode());

            final HttpResponse<String> events = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/events?user=User_001"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, events.statusCode());
            final JsonNode eventJson = JsonSupport.MAPPER.readTree(events.body());
            assertEquals(1, eventJson.get("events").size());
            assertEquals("User_001",
                    eventJson.get("events").get(0).get("userRef").asText());
            assertEquals("192.0.2.25",
                    eventJson.get("events").get(0).get("sourceNetwork").asText());

            final HttpResponse<String> ipEvents = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/events?ip=192.0.2.25"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ipEvents.statusCode());
            assertEquals(1,
                    JsonSupport.MAPPER.readTree(ipEvents.body()).get("events").size());

            final HttpResponse<String> regexEvents = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/events"
                                    + "?match=regex&user=%5EUser_%5B0-9%5D%2B%24"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, regexEvents.statusCode());
            assertEquals(1,
                    JsonSupport.MAPPER.readTree(regexEvents.body()).get("events").size());

            final HttpResponse<String> invalidRegex = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/events"
                                    + "?match=regex&user=%28"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidRegex.statusCode());

            final HttpResponse<String> writeAttempt = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, writeAttempt.statusCode());
        }
    }

    @Test
    void rejectsForgedProxyHeadersFromAnUntrustedAddress() throws Exception {
        final int port = freePort();
        final String proxySecret = "untrusted-test-proxy-secret-123456789";
        final Path proxySecretFile = Files.writeString(
                temporary.resolve("untrusted-proxy.secret"), proxySecret);
        final Path configuration = temporary.resolve("untrusted.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.auth.mode=proxy
                dashboard.auth.trustedProxies=192.0.2.0/24
                dashboard.auth.proxySecretFile=%s
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, proxySecretFile, temporary.resolve("untrusted-db")));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/summary"))
                            .header("X-Remote-User", "attacker")
                            .header("X-2FASKW-Role", "DASHBOARD_ADMIN")
                            .header("X-2FASKW-Proxy-Secret", proxySecret)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
        }
    }

    @Test
    void permitsLocalNetworkWithConfiguredViewerRoleAndIgnoresHeaders() throws Exception {
        final int port = freePort();
        final Path roles = temporary.resolve("local-roles.properties");
        Files.writeString(roles, "local-network=DASHBOARD_ADMIN\n");
        final Path configuration = temporary.resolve("local-viewer.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.auth.mode=local
                dashboard.auth.localAllowedCIDRs=127.0.0.1/32
                dashboard.auth.localRole=DASHBOARD_VIEWER
                dashboard.auth.rolesFile=%s
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, roles, temporary.resolve("local-viewer-db")));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final String base = "http://127.0.0.1:" + port + "/2faskw-dashboard";
            final HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/"))
                            .header("X-Forwarded-For", "192.0.2.99")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            final HttpResponse<String> summary = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/summary"))
                            .header("X-Remote-User", "attacker")
                            .header("X-2FASKW-Role", "DASHBOARD_ADMIN")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            final HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/health/ready"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            final HttpResponse<String> operatorOnly = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ingest-health"))
                            .header("X-Remote-User", "attacker")
                            .header("X-2FASKW-Role", "DASHBOARD_ADMIN")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, page.statusCode());
            assertEquals(200, summary.statusCode());
            assertEquals(200, health.statusCode());
            assertEquals(403, operatorOnly.statusCode());
        }
    }

    @Test
    void rejectsEveryUiPathFromOutsideTheLocalNetworks() throws Exception {
        final int port = freePort();
        final Path configuration = temporary.resolve("local-denied.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.auth.mode=local
                dashboard.auth.localAllowedCIDRs=192.0.2.0/24
                dashboard.auth.localRole=DASHBOARD_OPERATOR
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, temporary.resolve("local-denied-db")));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final String base = "http://127.0.0.1:" + port + "/2faskw-dashboard";
            for (String path : List.of(
                    "/",
                    "/app.js",
                    "/health/live",
                    "/health/ready",
                    "/api/v1/summary")) {
                final HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(base + path))
                                .header("X-Forwarded-For", "192.0.2.10")
                                .header("X-Remote-User", "attacker")
                                .header("X-2FASKW-Role", "DASHBOARD_ADMIN")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, response.statusCode(), path);
            }
        }
    }

    @Test
    void permitsOperatorEndpointsInLocalMode() throws Exception {
        final int port = freePort();
        final Path configuration = temporary.resolve("local-operator.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.auth.mode=local
                dashboard.auth.localAllowedCIDRs=127.0.0.1/32
                dashboard.auth.localRole=DASHBOARD_OPERATOR
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, temporary.resolve("local-operator-db")));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/2faskw-dashboard/api/v1/ingest-health"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        }
    }

    @Test
    void enforcesEndpointRolesAtTheApi() throws Exception {
        final int port = freePort();
        final String proxySecret = "roles-test-proxy-secret-123456789012";
        final Path proxySecretFile = Files.writeString(
                temporary.resolve("roles-proxy.secret"), proxySecret);
        final Path configuration = temporary.resolve("roles.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.auth.mode=proxy
                dashboard.auth.trustedProxies=127.0.0.1/32
                dashboard.auth.proxySecretFile=%s
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, proxySecretFile, temporary.resolve("roles-db")));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final URI uri = URI.create("http://127.0.0.1:" + port
                    + "/2faskw-dashboard/api/v1/ingest-health");
            final HttpResponse<String> viewer = client.send(
                    HttpRequest.newBuilder(uri)
                            .header("X-Remote-User", "viewer")
                            .header("X-2FASKW-Role", "DASHBOARD_VIEWER")
                            .header("X-2FASKW-Proxy-Secret", proxySecret)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            final HttpResponse<String> operator = client.send(
                    HttpRequest.newBuilder(uri)
                            .header("X-Remote-User", "operator")
                            .header("X-2FASKW-Role", "DASHBOARD_OPERATOR")
                            .header("X-2FASKW-Proxy-Secret", proxySecret)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            final HttpResponse<String> forgedAdmin = client.send(
                    HttpRequest.newBuilder(uri)
                            .header("X-Remote-User", "attacker")
                            .header("X-2FASKW-Role", "DASHBOARD_ADMIN")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(403, viewer.statusCode());
            assertEquals(200, operator.statusCode());
            assertEquals(403, forgedAdmin.statusCode());
        }
    }

    @Test
    void principalRoleScopesGenericQueriesSummariesAndLockState() throws Exception {
        final int port = freePort();
        final String proxySecret = "event-scope-proxy-secret-123456789012";
        final Path proxySecretFile = Files.writeString(
                temporary.resolve("event-scope-proxy.secret"), proxySecret);
        final Path database = temporary.resolve("event-scope-db");
        final Instant now = Instant.now();
        try (DashboardStore store = new DashboardStore(database)) {
            store.ingest(List.of(
                    event(now.minusSeconds(50), "1", "VERIFY", "OK", null, null),
                    event(now.minusSeconds(40), "2", "CHANGE_SAVE", "OK", null, null),
                    event(now.minusSeconds(30), "3", "API_USER_UPDATED", "OK", null, null),
                    event(now.minusSeconds(20), "4", "APIX_USER_UPDATED", "OK", null, null),
                    event(now.minusSeconds(10), "5", "UNRECOGNIZED", "OK", null, null),
                    event(
                            now.minusSeconds(5),
                            "6",
                            "VERIFY",
                            "LOCKED",
                            "locked-user",
                            now.plusSeconds(900).toEpochMilli())),
                    "test");
        }

        final Path configuration = temporary.resolve("event-scope.properties");
        Files.writeString(configuration, """
                dashboard.enabled=true
                dashboard.http.bindAddress=127.0.0.1
                dashboard.http.port=%d
                dashboard.auth.mode=proxy
                dashboard.auth.trustedProxies=127.0.0.1/32
                dashboard.auth.proxySecretFile=%s
                dashboard.ingest.enabled=false
                dashboard.storage.path=%s
                """.formatted(port, proxySecretFile, database));

        try (DashboardServer server =
                new DashboardServer(DashboardConfig.load(configuration))) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final String base = "http://127.0.0.1:" + port
                    + "/2faskw-dashboard/api/v1";

            final HttpResponse<String> viewerSummary = sendAs(
                    client, base + "/summary", "viewer", "DASHBOARD_VIEWER", proxySecret);
            final HttpResponse<String> viewerEvents = sendAs(
                    client, base + "/events", "viewer", "DASHBOARD_VIEWER", proxySecret);
            assertEquals(200, viewerSummary.statusCode());
            assertEquals(403, viewerEvents.statusCode());
            final JsonNode viewerSummaryJson =
                    JsonSupport.MAPPER.readTree(viewerSummary.body());
            assertEquals("OPERATIONAL",
                    viewerSummaryJson.path("visibility").path("scope").asText());
            assertFalse(viewerSummaryJson.path("visibility")
                    .path("eventsAvailable").asBoolean());
            assertTrue(viewerSummaryJson.path("lockedUserCount").isNull());

            final HttpResponse<String> operatorEvents = sendAs(
                    client, base + "/events", "operator", "DASHBOARD_OPERATOR", proxySecret);
            final HttpResponse<String> operatorExactAdmin = sendAs(
                    client,
                    base + "/events?event=API_USER_UPDATED",
                    "operator",
                    "DASHBOARD_OPERATOR",
                    proxySecret);
            final HttpResponse<String> operatorRegexAdmin = sendAs(
                    client,
                    base + "/events?match=regex&event=%5EAPI_.*%24",
                    "operator",
                    "DASHBOARD_OPERATOR",
                    proxySecret);
            final HttpResponse<String> operatorAdminRoute = sendAs(
                    client,
                    base + "/admin-api",
                    "operator",
                    "DASHBOARD_OPERATOR",
                    proxySecret);
            final HttpResponse<String> operatorSummary = sendAs(
                    client,
                    base + "/summary",
                    "operator",
                    "DASHBOARD_OPERATOR",
                    proxySecret);
            assertEquals(3, JsonSupport.MAPPER.readTree(operatorEvents.body())
                    .path("events").size());
            assertEquals(0, JsonSupport.MAPPER.readTree(operatorExactAdmin.body())
                    .path("events").size());
            assertEquals(0, JsonSupport.MAPPER.readTree(operatorRegexAdmin.body())
                    .path("events").size());
            assertEquals(403, operatorAdminRoute.statusCode());
            final JsonNode operatorSummaryJson =
                    JsonSupport.MAPPER.readTree(operatorSummary.body());
            assertEquals("OPERATIONAL",
                    operatorSummaryJson.path("visibility").path("scope").asText());
            assertTrue(operatorSummaryJson.path("lockedUserCount").isNull());

            final HttpResponse<String> auditorEvents = sendAs(
                    client, base + "/events", "auditor", "DASHBOARD_AUDITOR", proxySecret);
            final HttpResponse<String> auditorAdminRoute = sendAs(
                    client,
                    base + "/admin-api",
                    "auditor",
                    "DASHBOARD_AUDITOR",
                    proxySecret);
            final HttpResponse<String> auditorSummary = sendAs(
                    client,
                    base + "/summary",
                    "auditor",
                    "DASHBOARD_AUDITOR",
                    proxySecret);
            assertEquals(6, JsonSupport.MAPPER.readTree(auditorEvents.body())
                    .path("events").size());
            final JsonNode adminEvents = JsonSupport.MAPPER.readTree(
                    auditorAdminRoute.body()).path("events");
            assertEquals(1, adminEvents.size());
            assertEquals("API_USER_UPDATED", adminEvents.get(0).path("event").asText());
            final JsonNode auditorSummaryJson =
                    JsonSupport.MAPPER.readTree(auditorSummary.body());
            assertEquals("ALL",
                    auditorSummaryJson.path("visibility").path("scope").asText());
            assertEquals(1, auditorSummaryJson.path("lockedUserCount").asLong());
        }
    }

    private static NormalizedEvent event(
            final Instant time,
            final String idCharacter,
            final String type,
            final String result,
            final String user,
            final Long lockedUntil) {
        return new NormalizedEvent(
                1,
                idCharacter.repeat(64),
                time,
                time,
                "node-01",
                type,
                result,
                "test",
                user,
                "192.0.2.25",
                lockedUntil,
                "ok",
                "1",
                0,
                idCharacter.repeat(64));
    }

    private static HttpResponse<String> sendAs(
            final HttpClient client,
            final String uri,
            final String user,
            final String role,
            final String proxySecret) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(uri))
                        .header("X-Remote-User", user)
                        .header("X-2FASKW-Role", role)
                        .header("X-2FASKW-Proxy-Secret", proxySecret)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
