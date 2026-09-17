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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;

public final class DashboardServer implements AutoCloseable {

    private static final Pattern SAFE_FILTER = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SAFE_NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern SAFE_GENERATION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_NETWORK =
            Pattern.compile("[0-9A-Fa-f:.]+(?:/[0-9]{1,3})?");
    private static final int MAX_RATE_LIMIT_ENTRIES = 10_000;
    private final DashboardConfig config;
    private final DashboardStore store;
    private final DashboardAuthorizer authorizer;
    private final HttpServer uiServer;
    private final HttpsServer ingestServer;
    private final ScheduledExecutorService maintenance;
    private final RequestRateLimiter queryRateLimiter = new RequestRateLimiter(300);
    private final RequestRateLimiter ingestRateLimiter = new RequestRateLimiter(600);

    public DashboardServer(final DashboardConfig config) throws Exception {
        if (!config.enabled()) {
            throw new IllegalArgumentException(
                    "dashboard.enabled=false; validate the configuration before enabling the service");
        }
        this.config = config;
        store = new DashboardStore(config.storagePath());
        authorizer = new DashboardAuthorizer(config);

        uiServer = HttpServer.create(
                new InetSocketAddress(config.bindAddress(), config.port()), 128);
        uiServer.createContext(config.basePath(), new UiHandler());
        uiServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        if (config.ingestEnabled()) {
            ingestServer = HttpsServer.create(
                    new InetSocketAddress(config.ingestBindAddress(), config.ingestPort()), 128);
            ingestServer.setHttpsConfigurator(new HttpsConfigurator(TlsSupport.serverContext(
                    config.ingestKeyStore(),
                    config.ingestKeyStorePasswordFile(),
                    config.ingestTrustStore(),
                    config.ingestTrustStorePasswordFile())) {
                @Override
                public void configure(final com.sun.net.httpserver.HttpsParameters parameters) {
                    final SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
                    sslParameters.setNeedClientAuth(true);
                    sslParameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    parameters.setSSLParameters(sslParameters);
                }
            });
            ingestServer.createContext(
                    config.basePath() + "/ingest/v1/events", new IngestHandler());
            ingestServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        } else {
            ingestServer = null;
        }
        maintenance = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        uiServer.start();
        if (ingestServer != null) {
            ingestServer.start();
        }
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                store.deleteOlderThan(Instant.now().minus(Duration.ofDays(config.retentionDays())));
                store.deleteAggregatesOlderThan(
                        Instant.now().minus(Duration.ofDays(config.aggregateRetentionDays())));
                store.deleteAccessAuditOlderThan(
                        Instant.now().minus(Duration.ofDays(config.retentionDays())));
            } catch (SQLException e) {
                System.err.println("dashboard retention failed: " + e.getMessage());
            }
        }, 1, 24, TimeUnit.HOURS);
    }

    public void await() throws InterruptedException {
        Thread.currentThread().join();
    }

    private final class UiHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            addSecurityHeaders(exchange.getResponseHeaders());
            if (!authorizer.permitsSource(exchange)) {
                sendJson(exchange, 403, Map.of("error", "access_denied"));
                return;
            }
            final String relative = relativePath(exchange.getRequestURI());
            if (relative == null) {
                sendJson(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            if ("/health/live".equals(relative)) {
                sendJson(exchange, 200, Map.of("status", "UP"));
                return;
            }
            if ("/health/ready".equals(relative)) {
                sendJson(exchange, 200, Map.of("status", "UP", "storage", "ready"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            if (relative.startsWith("/api/")) {
                handleApi(exchange, relative);
                return;
            }
            serveStatic(exchange, relative);
        }
    }

    private void handleApi(final HttpExchange exchange, final String path) throws IOException {
        final DashboardAuthorizer.Role required = requiredRole(path);
        final DashboardAuthorizer.Principal principal = authorizer.authorize(exchange, required);
        if (principal == null) {
            sendJson(exchange, 403, Map.of("error", "access_denied"));
            return;
        }
        if (!queryRateLimiter.allow(principal.rateLimitKey())) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            sendJson(exchange, 429, Map.of("error", "rate_limited"));
            return;
        }
        final DashboardEventScope eventScope = DashboardEventPolicy.scopeFor(principal);
        final Map<String, String> query = parseQuery(exchange.getRequestURI());
        try {
            final TimeRange range = timeRange(query);
            final Object response;
            if ("/api/v1/summary".equals(path)) {
                response = summary(
                        eventScope,
                        principal,
                        range.from(),
                        range.to(),
                        parseTopMismatchLimit(query.get("topMismatchLimit")),
                        parseTopSourceNetworkLimit(query.get("topSourceNetworkLimit")));
            } else if ("/api/v1/authentication".equals(path)) {
                response = categoryEvents(
                        eventScope, range, query,
                        DashboardEventPolicy.RouteCategory.AUTHENTICATION);
            } else if ("/api/v1/self-service".equals(path)) {
                response = categoryEvents(
                        eventScope, range, query,
                        DashboardEventPolicy.RouteCategory.SELF_SERVICE);
            } else if ("/api/v1/admin-api".equals(path)) {
                response = categoryEvents(
                        eventScope, range, query,
                        DashboardEventPolicy.RouteCategory.ADMIN_API);
            } else if ("/api/v1/ingest-health".equals(path)) {
                response = Map.of(
                        "nodes", store.ingestHealth(),
                        "serverTime", Instant.now(),
                        "staleEventSeconds", config.staleEventSeconds(),
                        "parseFailureWarningCount", config.parseFailureWarningCount());
            } else if ("/api/v1/events".equals(path)) {
                response = eventPage(eventScope, range, query);
            } else if ("/api/v1/export".equals(path)) {
                if (!config.exportEnabled()) {
                    sendJson(exchange, 404, Map.of("error", "export_disabled"));
                    return;
                }
                sendExport(exchange, eventScope, range, query);
                store.recordAccess(principal.user(), principal.role().name(), "export",
                        "time_range", "OK", exchange.getRemoteAddress().getAddress().getHostAddress());
                return;
            } else {
                sendJson(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            store.recordAccess(principal.user(), principal.role().name(), "read", path, "OK",
                    exchange.getRemoteAddress().getAddress().getHostAddress());
            sendJson(exchange, 200, response);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "invalid_query", "message", e.getMessage()));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "storage_error"));
        }
    }

    private Map<String, Object> categoryEvents(
            final DashboardEventScope eventScope,
            final TimeRange range,
            final Map<String, String> query,
            final DashboardEventPolicy.RouteCategory category) throws SQLException {
        final List<Map<String, Object>> rows = store.categoryEvents(
                eventScope,
                category,
                range.from(),
                range.to(),
                filter(query, "event"),
                filter(query, "result"),
                nodeFilter(query),
                reasonFilter(query),
                userFilter(query),
                Math.min(config.maxPageSize(), 500));
        return Map.of("from", range.from(), "to", range.to(), "events", rows);
    }

    private Map<String, Object> eventPage(
            final DashboardEventScope eventScope,
            final TimeRange range,
            final Map<String, String> query) throws SQLException {
        final int limit = parseLimit(query.get("limit"));
        final DashboardStore.EventCursor cursor = decodeCursor(query.get("cursor"));
        final EventRegexFilter regexFilter = regexFilter(query);
        final List<Map<String, Object>> events = store.eventPage(
                eventScope,
                range.from(), range.to(),
                regexFilter == null ? filter(query, "event") : null,
                regexFilter == null ? filter(query, "result") : null,
                regexFilter == null ? nodeFilter(query) : null,
                regexFilter == null ? reasonFilter(query) : null,
                regexFilter == null ? userFilter(query) : null,
                regexFilter == null ? sourceNetworkFilter(query) : null,
                limit, cursor, regexFilter);
        final String next = events.size() == limit
                ? encodeCursor(events.getLast()) : null;
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("from", range.from());
        response.put("to", range.to());
        response.put("events", events);
        response.put("nextCursor", next);
        return response;
    }

    private void sendExport(
            final HttpExchange exchange,
            final DashboardEventScope eventScope,
            final TimeRange range,
            final Map<String, String> query) throws IOException, SQLException {
        final EventRegexFilter regexFilter = regexFilter(query);
        final List<Map<String, Object>> events = store.events(
                eventScope,
                range.from(), range.to(),
                regexFilter == null ? filter(query, "event") : null,
                regexFilter == null ? filter(query, "result") : null,
                regexFilter == null ? nodeFilter(query) : null,
                regexFilter == null ? reasonFilter(query) : null,
                regexFilter == null ? userFilter(query) : null,
                regexFilter == null ? sourceNetworkFilter(query) : null,
                config.maxExportRows(), 0, regexFilter);
        final StringBuilder csv = new StringBuilder(
                "occurred_at,node_id,event,result,reason,user_ref,source_network\n");
        for (Map<String, Object> event : events) {
            csv.append(csv(event.get("occurredAt"))).append(',')
                    .append(csv(event.get("nodeId"))).append(',')
                    .append(csv(event.get("event"))).append(',')
                    .append(csv(event.get("result"))).append(',')
                    .append(csv(event.get("reason"))).append(',')
                    .append(csv(event.get("userRef"))).append(',')
                    .append(csv(event.get("sourceNetwork"))).append('\n');
        }
        final byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.getResponseHeaders().set(
                "Content-Disposition", "attachment; filename=\"2faskw-dashboard-events.csv\"");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private Map<String, Object> summary(
            final DashboardEventScope eventScope,
            final DashboardAuthorizer.Principal principal,
            final Instant from,
            final Instant to,
            final int topMismatchLimit,
            final int topSourceNetworkLimit) throws SQLException {
        final Map<String, Object> response = store.summary(
                eventScope, from, to, topMismatchLimit, topSourceNetworkLimit);
        final Map<String, Object> visibility = new LinkedHashMap<>();
        visibility.put("scope", eventScope.externalName());
        visibility.put("lockedUsersAvailable", eventScope.allowsLockedUsers());
        visibility.put("eventsAvailable", principal.role().permits(
                DashboardAuthorizer.Role.DASHBOARD_OPERATOR));
        visibility.put("ingestHealthAvailable", principal.role().permits(
                DashboardAuthorizer.Role.DASHBOARD_OPERATOR));
        visibility.put("adminApiAvailable", principal.role().permits(
                DashboardAuthorizer.Role.DASHBOARD_AUDITOR));
        visibility.put("exportAvailable", config.exportEnabled()
                && principal.role().permits(DashboardAuthorizer.Role.DASHBOARD_AUDITOR));
        response.put("visibility", visibility);
        return response;
    }

    private static String csv(final Object value) {
        String text = value == null ? "" : value.toString();
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private final class IngestHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            addSecurityHeaders(exchange.getResponseHeaders());
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            if (!(exchange instanceof HttpsExchange httpsExchange)) {
                sendJson(exchange, 403, Map.of("error", "mtls_required"));
                return;
            }
            final byte[] body;
            try {
                body = readBounded(exchange.getRequestBody(), config.maxRequestBytes());
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 413, Map.of("error", "request_too_large"));
                return;
            }
            try {
                final IngestBatch batch = JsonSupport.MAPPER.readValue(body, IngestBatch.class);
                validateBatch(batch, certificateSans(httpsExchange));
                if (!ingestRateLimiter.allow(batch.nodeId())) {
                    exchange.getResponseHeaders().set("Retry-After", "60");
                    sendJson(exchange, 429, Map.of("error", "rate_limited"));
                    return;
                }
                final long detectedClockSkew = batch.events().stream()
                        .filter(event -> event.occurredAt().isAfter(
                                Instant.now().plusSeconds(config.allowedClockSkewSeconds())))
                        .count();
                final DashboardStore.IngestResult result;
                if (batch.events().isEmpty()) {
                    store.recordHeartbeat(
                            batch.nodeId(), batch.agentVersion(), batch.spoolBytes());
                    result = new DashboardStore.IngestResult(0, 0);
                } else {
                    result = store.ingest(
                            batch.events(),
                            batch.agentVersion(),
                            batch.spoolBytes(),
                            batch.parseFailureCount(),
                            batch.clockSkewCount() + detectedClockSkew);
                }
                sendJson(exchange, 200, Map.of(
                        "accepted", result.accepted(),
                        "duplicates", result.duplicates(),
                        "serverTime", Instant.now()));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("error", "invalid_batch", "message", e.getMessage()));
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "storage_error"));
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of("error", "invalid_batch"));
            }
        }
    }

    private void validateBatch(final IngestBatch batch, final Set<String> certificateSans) {
        if (batch == null || batch.schemaVersion() != 1 || batch.events() == null
                || batch.events().size() > config.maxBatchEvents()) {
            throw new IllegalArgumentException("invalid schema version or batch size");
        }
        if (batch.agentVersion() == null || batch.agentVersion().isBlank()
                || batch.agentVersion().length() > 32 || hasControl(batch.agentVersion())
                || batch.spoolBytes() < 0
                || batch.parseFailureCount() < 0 || batch.parseFailureCount() > 1_000_000
                || batch.clockSkewCount() < 0 || batch.clockSkewCount() > 1_000_000) {
            throw new IllegalArgumentException("invalid Agent status fields");
        }
        if (batch.nodeId() == null || !SAFE_NODE.matcher(batch.nodeId()).matches()
                || !certificateSans.contains(batch.nodeId())) {
            throw new IllegalArgumentException("nodeId does not match a certificate SAN");
        }
        final Instant maximumFuture = Instant.now().plus(Duration.ofHours(24));
        for (NormalizedEvent event : batch.events()) {
            if (event == null || event.schemaVersion() != 1
                    || !batch.nodeId().equals(event.nodeId())
                    || event.eventId() == null || !event.eventId().matches("[0-9a-f]{64}")
                    || event.lineDigest() == null || !event.lineDigest().matches("[0-9a-f]{64}")
                    || event.receivedAt() == null
                    || event.occurredAt() == null || event.occurredAt().isAfter(maximumFuture)
                    || event.event() == null || !SAFE_FILTER.matcher(event.event()).matches()
                    || event.result() == null || !SAFE_FILTER.matcher(event.result()).matches()
                    || event.reason() == null || !SAFE_REASON.matcher(event.reason()).matches()
                    || event.userRef() != null
                            && !PrivacyFilter.isValidUserReference(event.userRef())
                    || event.sourceNetwork() != null
                            && !SAFE_NETWORK.matcher(event.sourceNetwork()).matches()
                    || event.lockedUntil() != null
                            && (!"LOCKED".equals(event.result())
                                    || event.lockedUntil() <= event.occurredAt().toEpochMilli()
                                    || event.lockedUntil()
                                            > event.occurredAt()
                                                    .plus(Duration.ofDays(31))
                                                    .toEpochMilli())
                    || !"ok".equals(event.parseStatus())
                    || event.sourceGeneration() == null
                    || !SAFE_GENERATION.matcher(event.sourceGeneration()).matches()
                    || event.sourceOffset() < 0
                    || !event.eventId().equals(CryptoSupport.sha256(
                            event.nodeId() + '\n'
                                    + event.sourceGeneration() + '\n'
                                    + event.sourceOffset() + '\n'
                                    + event.lineDigest()))) {
                throw new IllegalArgumentException("event failed validation");
            }
        }
    }

    private static boolean hasControl(final String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static Set<String> certificateSans(final HttpsExchange exchange) throws Exception {
        final X509Certificate certificate =
                (X509Certificate) exchange.getSSLSession().getPeerCertificates()[0];
        final Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) {
            return Set.of();
        }
        final java.util.HashSet<String> result = new java.util.HashSet<>();
        for (List<?> entry : names) {
            final int type = (Integer) entry.get(0);
            if ((type == 2 || type == 6) && entry.get(1) instanceof String value) {
                result.add(value);
                if (value.startsWith("urn:2faskw:node:")) {
                    result.add(value.substring("urn:2faskw:node:".length()));
                }
            }
        }
        return result;
    }

    private void serveStatic(final HttpExchange exchange, final String relative) throws IOException {
        final String resource;
        final String contentType;
        if (relative.isEmpty() || "/".equals(relative) || "/index.html".equals(relative)) {
            resource = "/web/index.html";
            contentType = "text/html; charset=utf-8";
        } else if ("/app.css".equals(relative)) {
            resource = "/web/app.css";
            contentType = "text/css; charset=utf-8";
        } else if ("/app.js".equals(relative)) {
            resource = "/web/app.js";
            contentType = "text/javascript; charset=utf-8";
        } else {
            sendJson(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        try (InputStream input = DashboardServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendJson(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            final byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    private String relativePath(final URI uri) {
        final String path = uri.getPath();
        if (!path.equals(config.basePath()) && !path.startsWith(config.basePath() + "/")) {
            return null;
        }
        return path.substring(config.basePath().length());
    }

    private TimeRange timeRange(final Map<String, String> query) {
        final Instant to = query.containsKey("to") ? Instant.parse(query.get("to")) : Instant.now();
        final Instant from = query.containsKey("from")
                ? Instant.parse(query.get("from"))
                : to.minus(Duration.ofHours(config.defaultHours()));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(config.maxRangeDays())) > 0) {
            throw new IllegalArgumentException("query range exceeds configured maximum");
        }
        return new TimeRange(from, to);
    }

    private int parseLimit(final String raw) {
        if (raw == null) {
            return config.pageSize();
        }
        try {
            final int limit = Integer.parseInt(raw);
            if (limit < 1 || limit > config.maxPageSize()) {
                throw new IllegalArgumentException("limit is outside the configured range");
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer", e);
        }
    }

    private static int parseTopMismatchLimit(final String raw) {
        if (raw == null) {
            return DashboardStore.DEFAULT_TOP_MISMATCH_LIMIT;
        }
        try {
            final int limit = Integer.parseInt(raw);
            if (limit < 1 || limit > DashboardStore.MAX_TOP_MISMATCH_LIMIT) {
                throw new IllegalArgumentException(
                        "topMismatchLimit must be between 1 and "
                                + DashboardStore.MAX_TOP_MISMATCH_LIMIT);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "topMismatchLimit must be an integer", e);
        }
    }

    private static int parseTopSourceNetworkLimit(final String raw) {
        if (raw == null) {
            return DashboardStore.DEFAULT_TOP_SOURCE_NETWORK_LIMIT;
        }
        try {
            final int limit = Integer.parseInt(raw);
            if (limit < 1 || limit > DashboardStore.MAX_TOP_SOURCE_NETWORK_LIMIT) {
                throw new IllegalArgumentException(
                        "topSourceNetworkLimit must be between 1 and "
                                + DashboardStore.MAX_TOP_SOURCE_NETWORK_LIMIT);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "topSourceNetworkLimit must be an integer", e);
        }
    }

    private static String filter(final Map<String, String> query, final String name) {
        final String value = query.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!SAFE_FILTER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains invalid characters");
        }
        return value;
    }

    private static String nodeFilter(final Map<String, String> query) {
        final String value = query.get("node");
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!SAFE_NODE.matcher(value).matches()) {
            throw new IllegalArgumentException("node contains invalid characters");
        }
        return value;
    }

    private static String reasonFilter(final Map<String, String> query) {
        final String value = query.get("reason");
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!SAFE_REASON.matcher(value).matches()) {
            throw new IllegalArgumentException("reason contains invalid characters");
        }
        return value;
    }

    private static String userFilter(final Map<String, String> query) {
        final String value = userFilterValue(query);
        if (value == null) {
            return null;
        }
        if (!PrivacyFilter.isValidUserReference(value)) {
            throw new IllegalArgumentException("user contains invalid characters");
        }
        return value;
    }

    private static String userFilterValue(final Map<String, String> query) {
        final String user = blankToNull(query.get("user"));
        final String legacyUserRef = blankToNull(query.get("userRef"));
        if (user != null && !user.isBlank()
                && legacyUserRef != null && !legacyUserRef.isBlank()) {
            throw new IllegalArgumentException("user and userRef cannot both be specified");
        }
        return user != null ? user : legacyUserRef;
    }

    private static String sourceNetworkFilter(final Map<String, String> query) {
        final String value = blankToNull(query.get("ip"));
        if (value == null) {
            return null;
        }
        if (!SAFE_NETWORK.matcher(value).matches()) {
            throw new IllegalArgumentException("ip contains invalid characters");
        }
        return value;
    }

    private static EventRegexFilter regexFilter(final Map<String, String> query) {
        final String match = blankToNull(query.get("match"));
        if (match == null || "exact".equals(match)) {
            return null;
        }
        if (!"regex".equals(match)) {
            throw new IllegalArgumentException("match must be exact or regex");
        }
        return EventRegexFilter.compile(
                blankToNull(query.get("node")),
                blankToNull(query.get("event")),
                blankToNull(query.get("result")),
                blankToNull(query.get("reason")),
                userFilterValue(query),
                blankToNull(query.get("ip")));
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static DashboardStore.EventCursor decodeCursor(final String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            final String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (decoded.length() > 160) {
                throw new IllegalArgumentException("cursor is too long");
            }
            final int separator = decoded.lastIndexOf('|');
            if (separator < 1) {
                throw new IllegalArgumentException("cursor has no separator");
            }
            final Instant occurredAt = Instant.parse(decoded.substring(0, separator));
            final String eventId = decoded.substring(separator + 1);
            if (!eventId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("cursor event ID is invalid");
            }
            return new DashboardStore.EventCursor(occurredAt, eventId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor is invalid", e);
        }
    }

    private static String encodeCursor(final Map<String, Object> lastEvent) {
        final String value = lastEvent.get("occurredAt") + "|" + lastEvent.get("eventId");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, String> parseQuery(final URI uri) {
        final Map<String, String> result = new HashMap<>();
        final String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return result;
        }
        final String[] parameters = query.split("&");
        if (parameters.length > 12) {
            throw new IllegalArgumentException("too many query parameters");
        }
        for (String parameter : parameters) {
            final String[] pair = parameter.split("=", 2);
            final String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            final String value = pair.length == 2
                    ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (name.length() > 64 || value.length() > 256 || result.put(name, value) != null) {
                throw new IllegalArgumentException("invalid or duplicate query parameter");
            }
        }
        return result;
    }

    private static DashboardAuthorizer.Role requiredRole(final String path) {
        if ("/api/v1/admin-api".equals(path) || "/api/v1/export".equals(path)) {
            return DashboardAuthorizer.Role.DASHBOARD_AUDITOR;
        }
        if ("/api/v1/ingest-health".equals(path) || "/api/v1/events".equals(path)) {
            return DashboardAuthorizer.Role.DASHBOARD_OPERATOR;
        }
        return DashboardAuthorizer.Role.DASHBOARD_VIEWER;
    }

    private static byte[] readBounded(final InputStream input, final int maximum) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        final byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) {
                throw new IllegalArgumentException("request exceeds configured maximum");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void addSecurityHeaders(final Headers headers) {
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
                        + "base-uri 'none'; form-action 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    private static void sendJson(
            final HttpExchange exchange,
            final int status,
            final Object value) throws IOException {
        final byte[] body = JsonSupport.MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override
    public void close() {
        maintenance.shutdownNow();
        if (ingestServer != null) {
            ingestServer.stop(2);
        }
        uiServer.stop(2);
        store.close();
    }

    private record TimeRange(Instant from, Instant to) {
    }

    static final class RequestRateLimiter {
        private record Window(long minute, int count) {
        }

        private final int requestsPerMinute;
        private final int maximumEntries;
        private final LinkedHashMap<String, Window> windows =
                new LinkedHashMap<>(16, 0.75f, true);
        private long lastCleanupMinute = Long.MIN_VALUE;

        RequestRateLimiter(final int requestsPerMinute) {
            this(requestsPerMinute, MAX_RATE_LIMIT_ENTRIES);
        }

        RequestRateLimiter(final int requestsPerMinute, final int maximumEntries) {
            if (requestsPerMinute < 1 || maximumEntries < 1) {
                throw new IllegalArgumentException("rate-limit values must be positive");
            }
            this.requestsPerMinute = requestsPerMinute;
            this.maximumEntries = maximumEntries;
        }

        synchronized boolean allow(final String key) {
            final long minute = System.currentTimeMillis() / 60000;
            if (lastCleanupMinute != minute) {
                windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute - 1);
                lastCleanupMinute = minute;
            }
            final Window current = windows.get(key);
            if (current == null && windows.size() >= maximumEntries) {
                final var oldest = windows.entrySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            final Window updated = current == null || current.minute() != minute
                    ? new Window(minute, 1)
                    : new Window(minute, current.count() + 1);
            windows.put(key, updated);
            return updated.count() <= requestsPerMinute;
        }

        synchronized int size() {
            return windows.size();
        }

        synchronized boolean contains(final String key) {
            return windows.containsKey(key);
        }
    }
}
