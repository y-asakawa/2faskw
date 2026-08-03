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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class DashboardMain {

    private DashboardMain() {
    }

    public static void main(final String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("ERROR: " + safeMessage(e));
            System.exit(1);
        }
    }

    private static void run(final String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return;
        }
        final String command = args[0];
        final Map<String, String> options = options(args, 1);
        switch (command) {
            case "server" -> server(requiredPath(options, "--config"));
            case "agent" -> agent(requiredPath(options, "--config"));
            case "check" -> check(options);
            case "import" -> importLog(options);
            default -> throw new IllegalArgumentException("unknown command: " + command);
        }
    }

    private static void server(final Path configPath) throws Exception {
        final DashboardConfig config = DashboardConfig.load(configPath);
        final DashboardServer server = new DashboardServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "dashboard-shutdown"));
        server.start();
        System.out.printf(
                "2FAS-KW Dashboard started: http://%s:%d%s/%n",
                config.bindAddress(), config.port(), config.basePath());
        server.await();
    }

    private static void agent(final Path configPath) throws Exception {
        final AgentConfig config = AgentConfig.load(configPath);
        final DashboardAgent agent = new DashboardAgent(config);
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop, "dashboard-agent-shutdown"));
        agent.run();
    }

    private static void check(final Map<String, String> options) throws Exception {
        final boolean hasDashboard = options.containsKey("--config");
        final boolean hasAgent = options.containsKey("--agent-config");
        if (hasDashboard == hasAgent) {
            throw new IllegalArgumentException(
                    "check requires exactly one of --config or --agent-config");
        }
        final int javaVersion = Runtime.version().feature();
        if (javaVersion < 21) {
            throw new IllegalArgumentException("Java 21 or later is required");
        }
        if (hasDashboard) {
            final DashboardConfig config =
                    DashboardConfig.load(requiredPath(options, "--config"));
            final Path parent = config.storagePath().toAbsolutePath().getParent();
            if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
                throw new IllegalArgumentException("Dashboard storage parent is not writable");
            }
            System.out.println("OK: Dashboard configuration is valid");
            System.out.println("enabled=" + config.enabled());
            System.out.println("ui=" + config.bindAddress() + ":" + config.port()
                    + config.basePath());
            System.out.println("auth_mode=" + config.authMode());
            System.out.println("ingest_mtls=" + config.ingestEnabled());
        } else {
            final AgentConfig config =
                    AgentConfig.load(requiredPath(options, "--agent-config"));
            System.out.println("OK: Dashboard Agent configuration is valid");
            System.out.println("enabled=" + config.enabled());
            System.out.println("node_id=" + config.nodeId());
            System.out.println("source_readable=" + Files.isReadable(config.source()));
            System.out.println("dashboard_url=" + config.dashboardUrl());
            System.out.println("heartbeat_seconds=" + config.heartbeatSeconds());
        }
    }

    private static void importLog(final Map<String, String> options) throws Exception {
        final Path configPath = requiredPath(options, "--config");
        final Path input = requiredPath(options, "--file");
        final String nodeId = required(options, "--node-id");
        final boolean apply = options.containsKey("--apply");
        final DashboardConfig config = DashboardConfig.loadForOffline(configPath);
        final Path mapping = options.containsKey("--reason-mapping")
                ? Path.of(options.get("--reason-mapping")) : null;
        final PrivacyFilter privacy = new PrivacyFilter(
                PrivacyFilter.UserMode.PLAIN, PrivacyFilter.IpMode.PLAIN, null);
        if (!apply) {
            final DashboardImporter.ImportResult result = new DashboardImporter().run(
                    input, nodeId, null, privacy, new ReasonMapper(mapping), false);
            printImportResult(result, false);
            return;
        }
        try (DashboardStore store = new DashboardStore(config.storagePath())) {
            final DashboardImporter.ImportResult result = new DashboardImporter().run(
                    input, nodeId, store, privacy, new ReasonMapper(mapping), true);
            printImportResult(result, true);
        }
    }

    private static void printImportResult(
            final DashboardImporter.ImportResult result,
            final boolean apply) {
        System.out.println("mode=" + (apply ? "apply" : "dry-run"));
        System.out.println("lines=" + result.lines());
        System.out.println("valid=" + result.valid());
        System.out.println("parse_failures=" + result.parseFailures());
        System.out.println("accepted=" + result.accepted());
        System.out.println("duplicates=" + result.duplicates());
        System.out.println("first_event=" + value(result.firstEvent()));
        System.out.println("last_event=" + value(result.lastEvent()));
    }

    private static Map<String, String> options(final String[] args, final int start) {
        final Map<String, String> parsed = new HashMap<>();
        for (int i = start; i < args.length; i++) {
            final String name = args[i];
            if ("--apply".equals(name)) {
                if (parsed.put(name, "true") != null) {
                    throw new IllegalArgumentException("duplicate option: " + name);
                }
                continue;
            }
            if (!name.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("invalid option: " + name);
            }
            if (parsed.put(name, args[++i]) != null) {
                throw new IllegalArgumentException("duplicate option: " + name);
            }
        }
        return parsed;
    }

    private static Path requiredPath(final Map<String, String> options, final String name) {
        return Path.of(required(options, name));
    }

    private static String required(final Map<String, String> options, final String name) {
        final String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required option is missing: " + name);
        }
        return value;
    }

    private static Object value(final Object value) {
        return value == null ? "-" : value;
    }

    private static String safeMessage(final Exception exception) {
        final String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n\\t]", " ");
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  2faskw-dashboard server --config <dashboard.properties>
                  2faskw-dashboard agent --config <agent.properties>
                  2faskw-dashboard check --config <dashboard.properties>
                  2faskw-dashboard check --agent-config <agent.properties>
                  2faskw-dashboard import --config <dashboard.properties> \
                      --node-id <opaque-node-id> --file <audit.log[.gz]> [--apply]
                """);
    }
}
