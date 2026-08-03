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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DashboardStore implements AutoCloseable {

    private static final int SCHEMA_VERSION = 4;
    private static final int LOCKED_USER_DISPLAY_LIMIT = 100;
    static final int DEFAULT_TOP_MISMATCH_LIMIT = 3;
    static final int MAX_TOP_MISMATCH_LIMIT = 100;
    static final int DEFAULT_TOP_SOURCE_NETWORK_LIMIT = 3;
    static final int MAX_TOP_SOURCE_NETWORK_LIMIT = 100;

    public record IngestResult(int accepted, int duplicates) {
    }

    public record EventCursor(Instant occurredAt, String eventId) {
    }

    private final String jdbcUrl;

    public DashboardStore(final Path path) throws Exception {
        final Path absolute = path.toAbsolutePath().normalize();
        final Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        jdbcUrl = "jdbc:h2:file:" + absolute + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        migrate();
    }

    DashboardStore(final String jdbcUrl) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    private void migrate() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dashboard_schema_version (
                      singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
                      schema_version INTEGER NOT NULL,
                      applied_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            int currentVersion = 0;
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT schema_version FROM dashboard_schema_version WHERE singleton_id = 1")) {
                if (resultSet.next()) {
                    currentVersion = resultSet.getInt(1);
                }
            }
            if (currentVersion > SCHEMA_VERSION) {
                throw new SQLException(
                        "Dashboard schema is newer than this application: " + currentVersion);
            }
            if (currentVersion == SCHEMA_VERSION) {
                return;
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dashboard_event (
                      event_id VARCHAR(64) PRIMARY KEY,
                      schema_version SMALLINT NOT NULL,
                      occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      received_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      node_id VARCHAR(64) NOT NULL,
                      event_type VARCHAR(64) NOT NULL,
                      result VARCHAR(32) NOT NULL,
                      reason VARCHAR(64) NOT NULL,
                      user_ref VARCHAR(255),
                      source_network VARCHAR(64),
                      locked_until BIGINT,
                      parse_status VARCHAR(16) NOT NULL,
                      source_generation VARCHAR(64) NOT NULL,
                      source_offset BIGINT NOT NULL,
                      line_digest VARCHAR(64) NOT NULL
                    )
                    """);
            statement.execute("""
                    ALTER TABLE dashboard_event
                    ALTER COLUMN user_ref VARCHAR(255)
                    """);
            statement.execute("""
                    ALTER TABLE dashboard_event
                    ADD COLUMN IF NOT EXISTS locked_until BIGINT
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_event_occurred
                    ON dashboard_event(occurred_at)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_event_type_result
                    ON dashboard_event(event_type, result, occurred_at)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_event_node
                    ON dashboard_event(node_id, occurred_at)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_event_reason
                    ON dashboard_event(reason, occurred_at)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_event_user
                    ON dashboard_event(user_ref, occurred_at)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_event_source_network
                    ON dashboard_event(source_network, occurred_at)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dashboard_hourly (
                      bucket_start TIMESTAMP WITH TIME ZONE NOT NULL,
                      node_id VARCHAR(64) NOT NULL,
                      event_type VARCHAR(64) NOT NULL,
                      result VARCHAR(32) NOT NULL,
                      reason VARCHAR(64) NOT NULL,
                      event_count BIGINT NOT NULL,
                      last_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY(bucket_start, node_id, event_type, result, reason)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dashboard_ingest_state (
                      node_id VARCHAR(64) PRIMARY KEY,
                      last_received_at TIMESTAMP WITH TIME ZONE,
                      last_occurred_at TIMESTAMP WITH TIME ZONE,
                      last_event_id VARCHAR(64),
                      accepted_count BIGINT NOT NULL DEFAULT 0,
                      duplicate_count BIGINT NOT NULL DEFAULT 0,
                      parse_failure_count BIGINT NOT NULL DEFAULT 0,
                      clock_skew_count BIGINT NOT NULL DEFAULT 0,
                      agent_spool_bytes BIGINT NOT NULL DEFAULT 0,
                      agent_version VARCHAR(32)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dashboard_access_audit (
                      id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      actor VARCHAR(128) NOT NULL,
                      role_name VARCHAR(64) NOT NULL,
                      action_name VARCHAR(64) NOT NULL,
                      query_scope VARCHAR(512),
                      result VARCHAR(32) NOT NULL,
                      remote_address VARCHAR(64)
                    )
                    """);
            try (PreparedStatement version = connection.prepareStatement("""
                    MERGE INTO dashboard_schema_version (
                      singleton_id, schema_version, applied_at
                    ) KEY(singleton_id) VALUES (1, ?, ?)
                    """)) {
                version.setInt(1, SCHEMA_VERSION);
                version.setObject(2, utc(Instant.now()));
                version.executeUpdate();
            }
        }
    }

    public IngestResult ingest(final List<NormalizedEvent> events, final String agentVersion)
            throws SQLException {
        return ingest(events, agentVersion, 0, 0, 0);
    }

    public IngestResult ingest(
            final List<NormalizedEvent> events,
            final String agentVersion,
            final long agentSpoolBytes) throws SQLException {
        return ingest(events, agentVersion, agentSpoolBytes, 0, 0);
    }

    public IngestResult ingest(
            final List<NormalizedEvent> events,
            final String agentVersion,
            final long agentSpoolBytes,
            final long parseFailureCount,
            final long clockSkewCount) throws SQLException {
        if (events.isEmpty()) {
            return new IngestResult(0, 0);
        }
        int accepted = 0;
        int duplicates = 0;
        final Map<String, Integer> acceptedByNode = new LinkedHashMap<>();
        final Map<String, Integer> duplicatesByNode = new LinkedHashMap<>();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (NormalizedEvent event : events) {
                    if (insertEvent(connection, event)) {
                        accepted++;
                        acceptedByNode.merge(event.nodeId(), 1, Integer::sum);
                        incrementHourly(connection, event);
                    } else {
                        duplicates++;
                        duplicatesByNode.merge(event.nodeId(), 1, Integer::sum);
                    }
                }
                updateIngestState(
                        connection,
                        events,
                        agentVersion,
                        agentSpoolBytes,
                        acceptedByNode,
                        duplicatesByNode,
                        parseFailureCount,
                        clockSkewCount);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
        return new IngestResult(accepted, duplicates);
    }

    public void recordHeartbeat(
            final String nodeId,
            final String agentVersion,
            final long agentSpoolBytes) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE dashboard_ingest_state
                        SET last_received_at = ?, agent_spool_bytes = ?, agent_version = ?
                        WHERE node_id = ?
                        """)) {
                    statement.setObject(1, utc(Instant.now()));
                    statement.setLong(2, agentSpoolBytes);
                    statement.setString(3, agentVersion);
                    statement.setString(4, nodeId);
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO dashboard_ingest_state (
                              node_id, last_received_at, accepted_count, duplicate_count,
                              parse_failure_count, clock_skew_count, agent_spool_bytes,
                              agent_version
                            ) VALUES (?, ?, 0, 0, 0, 0, ?, ?)
                            """)) {
                        statement.setString(1, nodeId);
                        statement.setObject(2, utc(Instant.now()));
                        statement.setLong(3, agentSpoolBytes);
                        statement.setString(4, agentVersion);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private boolean insertEvent(final Connection connection, final NormalizedEvent event)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO dashboard_event (
                  event_id, schema_version, occurred_at, received_at, node_id, event_type,
                  result, reason, user_ref, source_network, locked_until, parse_status,
                  source_generation, source_offset, line_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.eventId());
            statement.setInt(2, event.schemaVersion());
            statement.setObject(3, utc(event.occurredAt()));
            statement.setObject(4, utc(event.receivedAt()));
            statement.setString(5, event.nodeId());
            statement.setString(6, event.event());
            statement.setString(7, event.result());
            statement.setString(8, event.reason());
            statement.setString(9, event.userRef());
            statement.setString(10, event.sourceNetwork());
            if (event.lockedUntil() == null) {
                statement.setNull(11, java.sql.Types.BIGINT);
            } else {
                statement.setLong(11, event.lockedUntil());
            }
            statement.setString(12, event.parseStatus());
            statement.setString(13, event.sourceGeneration());
            statement.setLong(14, event.sourceOffset());
            statement.setString(15, event.lineDigest());
            try {
                return statement.executeUpdate() == 1;
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    return false;
                }
                throw e;
            }
        }
    }

    private void incrementHourly(final Connection connection, final NormalizedEvent event)
            throws SQLException {
        final OffsetDateTime bucket = utc(event.occurredAt().truncatedTo(ChronoUnit.HOURS));
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE dashboard_hourly
                SET event_count = event_count + 1, last_updated_at = ?
                WHERE bucket_start = ? AND node_id = ? AND event_type = ? AND result = ? AND reason = ?
                """)) {
            update.setObject(1, utc(Instant.now()));
            update.setObject(2, bucket);
            update.setString(3, event.nodeId());
            update.setString(4, event.event());
            update.setString(5, event.result());
            update.setString(6, event.reason());
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO dashboard_hourly (
                  bucket_start, node_id, event_type, result, reason, event_count, last_updated_at
                ) VALUES (?, ?, ?, ?, ?, 1, ?)
                """)) {
            insert.setObject(1, bucket);
            insert.setString(2, event.nodeId());
            insert.setString(3, event.event());
            insert.setString(4, event.result());
            insert.setString(5, event.reason());
            insert.setObject(6, utc(Instant.now()));
            insert.executeUpdate();
        }
    }

    private void updateIngestState(
            final Connection connection,
            final List<NormalizedEvent> events,
            final String agentVersion,
            final long agentSpoolBytes,
            final Map<String, Integer> acceptedByNode,
            final Map<String, Integer> duplicatesByNode,
            final long parseFailureCount,
            final long clockSkewCount) throws SQLException {
        final Map<String, List<NormalizedEvent>> byNode = new LinkedHashMap<>();
        for (NormalizedEvent event : events) {
            byNode.computeIfAbsent(event.nodeId(), ignored -> new ArrayList<>()).add(event);
        }
        for (Map.Entry<String, List<NormalizedEvent>> entry : byNode.entrySet()) {
            final NormalizedEvent latest = entry.getValue().stream()
                    .max(java.util.Comparator.comparing(NormalizedEvent::occurredAt))
                    .orElseThrow();
            try (PreparedStatement merge = connection.prepareStatement("""
                    MERGE INTO dashboard_ingest_state (
                      node_id, last_received_at, last_occurred_at, last_event_id,
                      accepted_count, duplicate_count, parse_failure_count,
                      clock_skew_count, agent_spool_bytes, agent_version
                    ) KEY(node_id) VALUES (?, ?, ?, ?,
                      COALESCE((SELECT accepted_count FROM dashboard_ingest_state WHERE node_id = ?), 0) + ?,
                      COALESCE((SELECT duplicate_count FROM dashboard_ingest_state WHERE node_id = ?), 0) + ?,
                      COALESCE((SELECT parse_failure_count FROM dashboard_ingest_state WHERE node_id = ?), 0) + ?,
                      COALESCE((SELECT clock_skew_count FROM dashboard_ingest_state WHERE node_id = ?), 0) + ?,
                      ?,
                      ?)
                    """)) {
                merge.setString(1, entry.getKey());
                merge.setObject(2, utc(Instant.now()));
                merge.setObject(3, utc(latest.occurredAt()));
                merge.setString(4, latest.eventId());
                merge.setString(5, entry.getKey());
                merge.setLong(6, acceptedByNode.getOrDefault(entry.getKey(), 0));
                merge.setString(7, entry.getKey());
                merge.setLong(8, duplicatesByNode.getOrDefault(entry.getKey(), 0));
                merge.setString(9, entry.getKey());
                merge.setLong(
                        10,
                        acceptedByNode.getOrDefault(entry.getKey(), 0) > 0
                                ? parseFailureCount : 0);
                merge.setString(11, entry.getKey());
                merge.setLong(
                        12,
                        acceptedByNode.getOrDefault(entry.getKey(), 0) > 0
                                ? clockSkewCount : 0);
                merge.setLong(13, agentSpoolBytes);
                merge.setString(14, agentVersion);
                merge.executeUpdate();
            }
        }
    }

    public Map<String, Object> summary(final Instant from, final Instant to) throws SQLException {
        return summary(
                from, to, Instant.now(),
                DEFAULT_TOP_MISMATCH_LIMIT, DEFAULT_TOP_SOURCE_NETWORK_LIMIT);
    }

    public Map<String, Object> summary(
            final Instant from,
            final Instant to,
            final int topMismatchLimit) throws SQLException {
        return summary(
                from, to, Instant.now(),
                topMismatchLimit, DEFAULT_TOP_SOURCE_NETWORK_LIMIT);
    }

    public Map<String, Object> summary(
            final Instant from,
            final Instant to,
            final int topMismatchLimit,
            final int topSourceNetworkLimit) throws SQLException {
        return summary(
                from, to, Instant.now(), topMismatchLimit, topSourceNetworkLimit);
    }

    Map<String, Object> summary(
            final Instant from,
            final Instant to,
            final Instant asOf) throws SQLException {
        return summary(
                from, to, asOf,
                DEFAULT_TOP_MISMATCH_LIMIT, DEFAULT_TOP_SOURCE_NETWORK_LIMIT);
    }

    Map<String, Object> summary(
            final Instant from,
            final Instant to,
            final Instant asOf,
            final int topMismatchLimit) throws SQLException {
        return summary(
                from, to, asOf,
                topMismatchLimit, DEFAULT_TOP_SOURCE_NETWORK_LIMIT);
    }

    Map<String, Object> summary(
            final Instant from,
            final Instant to,
            final Instant asOf,
            final int topMismatchLimit,
            final int topSourceNetworkLimit) throws SQLException {
        if (topMismatchLimit < 1 || topMismatchLimit > MAX_TOP_MISMATCH_LIMIT) {
            throw new IllegalArgumentException(
                    "topMismatchLimit must be between 1 and "
                            + MAX_TOP_MISMATCH_LIMIT);
        }
        if (topSourceNetworkLimit < 1
                || topSourceNetworkLimit > MAX_TOP_SOURCE_NETWORK_LIMIT) {
            throw new IllegalArgumentException(
                    "topSourceNetworkLimit must be between 1 and "
                            + MAX_TOP_SOURCE_NETWORK_LIMIT);
        }
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("from", from);
        summary.put("to", to);
        final Map<CountKey, Long> totals = new LinkedHashMap<>();
        final List<Map<String, Object>> topMismatchUsers;
        final List<Map<String, Object>> topSourceNetworks;
        final List<Map<String, Object>> topSourceMismatchNetworks;
        final LockedUsers lockedUsers;
        final Instant firstFullHour = ceilingHour(from);
        final Instant lastFullHourExclusive = to.truncatedTo(ChronoUnit.HOURS);
        try (Connection connection = connection()) {
            if (firstFullHour.isBefore(lastFullHourExclusive)) {
                addRawCounts(connection, totals, from, firstFullHour);
                addHourlyCounts(connection, totals, firstFullHour, lastFullHourExclusive);
                addRawCounts(connection, totals, lastFullHourExclusive, to);
            } else {
                addRawCounts(connection, totals, from, to);
            }
            topMismatchUsers = topMismatchUsers(connection, from, to, topMismatchLimit);
            topSourceNetworks =
                    topSourceNetworks(connection, from, to, topSourceNetworkLimit);
            topSourceMismatchNetworks =
                    topSourceMismatchNetworks(connection, from, to, topSourceNetworkLimit);
            lockedUsers = lockedUsers(connection, asOf);
        }
        final List<Map<String, Object>> counts = totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "event", entry.getKey().event(),
                        "result", entry.getKey().result(),
                        "count", entry.getValue()))
                .toList();
        final long graphicalSuccess = totals.getOrDefault(new CountKey("VERIFY", "OK"), 0L);
        final long graphicalFailure = totals.getOrDefault(new CountKey("VERIFY", "FAIL"), 0L);
        summary.put("counts", counts);
        summary.put("graphicalMatrixSuccess", graphicalSuccess);
        summary.put("graphicalMatrixFailure", graphicalFailure);
        final long attempts = graphicalSuccess + graphicalFailure;
        summary.put("graphicalMatrixFailureRate",
                attempts == 0 ? null : (double) graphicalFailure / attempts);
        summary.put("topMismatchLimit", topMismatchLimit);
        summary.put("topMismatchUsers", topMismatchUsers);
        summary.put("topSourceNetworkLimit", topSourceNetworkLimit);
        summary.put("topSourceNetworks", topSourceNetworks);
        summary.put("topSourceMismatchNetworks", topSourceMismatchNetworks);
        summary.put("lockedUserCount", lockedUsers.total());
        summary.put("lockedUsers", lockedUsers.rows());
        summary.put("lockedUsersAsOf", asOf);
        return summary;
    }

    private static List<Map<String, Object>> topMismatchUsers(
            final Connection connection,
            final Instant from,
            final Instant to,
            final int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT user_ref, COUNT(*) AS mismatch_count
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                  AND event_type = 'VERIFY' AND result = 'FAIL'
                  AND user_ref IS NOT NULL
                GROUP BY user_ref
                ORDER BY mismatch_count DESC, user_ref
                LIMIT ?
                """)) {
            statement.setObject(1, utc(from));
            statement.setObject(2, utc(to));
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(Map.of(
                            "userRef", resultSet.getString("user_ref"),
                            "count", resultSet.getLong("mismatch_count")));
                }
                return rows;
            }
        }
    }

    private static List<Map<String, Object>> topSourceNetworks(
            final Connection connection,
            final Instant from,
            final Instant to,
            final int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_network, COUNT(*) AS authentication_count
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                  AND event_type = 'VERIFY'
                  AND result IN ('OK', 'FAIL', 'LOCKED')
                  AND source_network IS NOT NULL
                GROUP BY source_network
                ORDER BY authentication_count DESC, source_network
                LIMIT ?
                """)) {
            statement.setObject(1, utc(from));
            statement.setObject(2, utc(to));
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(Map.of(
                            "sourceNetwork", resultSet.getString("source_network"),
                            "count", resultSet.getLong("authentication_count")));
                }
                return rows;
            }
        }
    }

    private static List<Map<String, Object>> topSourceMismatchNetworks(
            final Connection connection,
            final Instant from,
            final Instant to,
            final int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_network, COUNT(*) AS mismatch_count
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                  AND event_type = 'VERIFY'
                  AND result = 'FAIL'
                  AND source_network IS NOT NULL
                GROUP BY source_network
                ORDER BY mismatch_count DESC, source_network
                LIMIT ?
                """)) {
            statement.setObject(1, utc(from));
            statement.setObject(2, utc(to));
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(Map.of(
                            "sourceNetwork", resultSet.getString("source_network"),
                            "count", resultSet.getLong("mismatch_count")));
                }
                return rows;
            }
        }
    }

    private static LockedUsers lockedUsers(
            final Connection connection,
            final Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH lock_transitions AS (
                  SELECT node_id, user_ref, occurred_at, event_id, result, locked_until,
                         ROW_NUMBER() OVER (
                           PARTITION BY user_ref
                           ORDER BY occurred_at DESC, event_id DESC
                         ) AS row_number
                  FROM dashboard_event
                  WHERE user_ref IS NOT NULL
                    AND (
                      (result = 'LOCKED' AND locked_until IS NOT NULL)
                      OR (
                        result = 'OK'
                        AND event_type IN (
                          'VERIFY', 'CHANGE_VERIFY', 'API_UNLOCKED', 'API_USER_RESET',
                          'API_METHOD_CHANGED', 'CHANGE_METHOD_SAVE',
                          'FORCE_SEQUENCE_CHANGE_SAVE'
                        )
                      )
                    )
                ),
                current_locks AS (
                  SELECT node_id, user_ref, locked_until
                  FROM lock_transitions
                  WHERE row_number = 1 AND result = 'LOCKED' AND locked_until > ?
                )
                SELECT node_id, user_ref, locked_until, COUNT(*) OVER () AS total_count
                FROM current_locks
                ORDER BY locked_until, user_ref, node_id
                LIMIT ?
                """)) {
            statement.setLong(1, asOf.toEpochMilli());
            statement.setInt(2, LOCKED_USER_DISPLAY_LIMIT);
            try (ResultSet resultSet = statement.executeQuery()) {
                final List<Map<String, Object>> rows = new ArrayList<>();
                long total = 0;
                while (resultSet.next()) {
                    total = resultSet.getLong("total_count");
                    rows.add(Map.of(
                            "nodeId", resultSet.getString("node_id"),
                            "userRef", resultSet.getString("user_ref"),
                            "lockedUntil", Instant.ofEpochMilli(
                                    resultSet.getLong("locked_until"))));
                }
                return new LockedUsers(total, rows);
            }
        }
    }

    private record LockedUsers(long total, List<Map<String, Object>> rows) {
    }

    private static Instant ceilingHour(final Instant value) {
        final Instant floor = value.truncatedTo(ChronoUnit.HOURS);
        return value.equals(floor) ? floor : floor.plus(1, ChronoUnit.HOURS);
    }

    private static void addRawCounts(
            final Connection connection,
            final Map<CountKey, Long> totals,
            final Instant from,
            final Instant to) throws SQLException {
        if (!from.isBefore(to)) {
            return;
        }
        addCounts(connection, totals, """
                SELECT event_type, result, COUNT(*) AS total
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY event_type, result
                """, from, to);
    }

    private static void addHourlyCounts(
            final Connection connection,
            final Map<CountKey, Long> totals,
            final Instant from,
            final Instant to) throws SQLException {
        addCounts(connection, totals, """
                SELECT event_type, result, SUM(event_count) AS total
                FROM dashboard_hourly
                WHERE bucket_start >= ? AND bucket_start < ?
                GROUP BY event_type, result
                """, from, to);
    }

    private static void addCounts(
            final Connection connection,
            final Map<CountKey, Long> totals,
            final String sql,
            final Instant from,
            final Instant to) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, utc(from));
            statement.setObject(2, utc(to));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    totals.merge(
                            new CountKey(
                                    resultSet.getString("event_type"),
                                    resultSet.getString("result")),
                            resultSet.getLong("total"),
                            Long::sum);
                }
            }
        }
    }

    private record CountKey(String event, String result) implements Comparable<CountKey> {
        @Override
        public int compareTo(final CountKey other) {
            final int eventOrder = event.compareTo(other.event);
            return eventOrder != 0 ? eventOrder : result.compareTo(other.result);
        }
    }

    public List<Map<String, Object>> events(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final int limit,
            final int offset) throws SQLException {
        return events(from, to, eventType, result, null, null, null, limit, offset);
    }

    public List<Map<String, Object>> events(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final int limit,
            final int offset) throws SQLException {
        return events(
                from, to, eventType, result, nodeId, reason, userRef, limit, offset, null);
    }

    public List<Map<String, Object>> events(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final int limit,
            final int offset,
            final EventRegexFilter regexFilter) throws SQLException {
        return events(
                from, to, eventType, result, nodeId, reason, userRef, null,
                limit, offset, regexFilter);
    }

    public List<Map<String, Object>> events(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final String sourceNetwork,
            final int limit,
            final int offset,
            final EventRegexFilter regexFilter) throws SQLException {
        final StringBuilder sql = new StringBuilder("""
                SELECT event_id, occurred_at, received_at, node_id, event_type, result, reason,
                       user_ref, source_network, locked_until
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                """);
        if (eventType != null) {
            sql.append(" AND event_type = ?");
        }
        if (result != null) {
            sql.append(" AND result = ?");
        }
        if (nodeId != null) {
            sql.append(" AND node_id = ?");
        }
        if (reason != null) {
            sql.append(" AND reason = ?");
        }
        if (userRef != null) {
            sql.append(" AND user_ref = ?");
        }
        if (sourceNetwork != null) {
            sql.append(" AND source_network = ?");
        }
        sql.append(" ORDER BY occurred_at DESC, event_id DESC");
        if (regexFilter == null) {
            sql.append(" LIMIT ? OFFSET ?");
        }

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setObject(parameter++, utc(from));
            statement.setObject(parameter++, utc(to));
            if (eventType != null) {
                statement.setString(parameter++, eventType);
            }
            if (result != null) {
                statement.setString(parameter++, result);
            }
            if (nodeId != null) {
                statement.setString(parameter++, nodeId);
            }
            if (reason != null) {
                statement.setString(parameter++, reason);
            }
            if (userRef != null) {
                statement.setString(parameter++, userRef);
            }
            if (sourceNetwork != null) {
                statement.setString(parameter++, sourceNetwork);
            }
            if (regexFilter == null) {
                statement.setInt(parameter++, limit);
                statement.setInt(parameter, offset);
            }
            return readEvents(statement, regexFilter, limit, offset);
        }
    }

    public List<Map<String, Object>> categoryEvents(
            final Instant from,
            final Instant to,
            final Set<String> eventTypes,
            final String eventPrefix,
            final String requestedEvent,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final int limit) throws SQLException {
        if (eventTypes.isEmpty() && eventPrefix == null) {
            return List.of();
        }
        if (requestedEvent != null
                && !eventTypes.contains(requestedEvent)
                && (eventPrefix == null || !requestedEvent.startsWith(eventPrefix))) {
            return List.of();
        }

        final StringBuilder sql = new StringBuilder("""
                SELECT event_id, occurred_at, received_at, node_id, event_type, result, reason,
                       user_ref, source_network, locked_until
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                """);
        if (requestedEvent != null) {
            sql.append(" AND event_type = ?");
        } else {
            sql.append(" AND (");
            if (!eventTypes.isEmpty()) {
                sql.append("event_type IN (");
                sql.append("?, ".repeat(eventTypes.size()));
                sql.setLength(sql.length() - 2);
                sql.append(')');
            }
            if (eventPrefix != null) {
                if (!eventTypes.isEmpty()) {
                    sql.append(" OR ");
                }
                sql.append("event_type LIKE ?");
            }
            sql.append(')');
        }
        if (result != null) {
            sql.append(" AND result = ?");
        }
        if (nodeId != null) {
            sql.append(" AND node_id = ?");
        }
        if (reason != null) {
            sql.append(" AND reason = ?");
        }
        if (userRef != null) {
            sql.append(" AND user_ref = ?");
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setObject(parameter++, utc(from));
            statement.setObject(parameter++, utc(to));
            if (requestedEvent != null) {
                statement.setString(parameter++, requestedEvent);
            } else {
                for (String eventType : eventTypes) {
                    statement.setString(parameter++, eventType);
                }
                if (eventPrefix != null) {
                    statement.setString(parameter++, eventPrefix + "%");
                }
            }
            if (result != null) {
                statement.setString(parameter++, result);
            }
            if (nodeId != null) {
                statement.setString(parameter++, nodeId);
            }
            if (reason != null) {
                statement.setString(parameter++, reason);
            }
            if (userRef != null) {
                statement.setString(parameter++, userRef);
            }
            statement.setInt(parameter, limit);
            return readEvents(statement);
        }
    }

    public List<Map<String, Object>> eventPage(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final int limit,
            final EventCursor cursor) throws SQLException {
        return eventPage(
                from, to, eventType, result, nodeId, reason, userRef, limit, cursor, null);
    }

    public List<Map<String, Object>> eventPage(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final int limit,
            final EventCursor cursor,
            final EventRegexFilter regexFilter) throws SQLException {
        return eventPage(
                from, to, eventType, result, nodeId, reason, userRef, null,
                limit, cursor, regexFilter);
    }

    public List<Map<String, Object>> eventPage(
            final Instant from,
            final Instant to,
            final String eventType,
            final String result,
            final String nodeId,
            final String reason,
            final String userRef,
            final String sourceNetwork,
            final int limit,
            final EventCursor cursor,
            final EventRegexFilter regexFilter) throws SQLException {
        final StringBuilder sql = new StringBuilder("""
                SELECT event_id, occurred_at, received_at, node_id, event_type, result, reason,
                       user_ref, source_network, locked_until
                FROM dashboard_event
                WHERE occurred_at >= ? AND occurred_at < ?
                """);
        if (eventType != null) {
            sql.append(" AND event_type = ?");
        }
        if (result != null) {
            sql.append(" AND result = ?");
        }
        if (nodeId != null) {
            sql.append(" AND node_id = ?");
        }
        if (reason != null) {
            sql.append(" AND reason = ?");
        }
        if (userRef != null) {
            sql.append(" AND user_ref = ?");
        }
        if (sourceNetwork != null) {
            sql.append(" AND source_network = ?");
        }
        if (cursor != null) {
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND event_id < ?))");
        }
        sql.append(" ORDER BY occurred_at DESC, event_id DESC");
        if (regexFilter == null) {
            sql.append(" LIMIT ?");
        }

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setObject(parameter++, utc(from));
            statement.setObject(parameter++, utc(to));
            if (eventType != null) {
                statement.setString(parameter++, eventType);
            }
            if (result != null) {
                statement.setString(parameter++, result);
            }
            if (nodeId != null) {
                statement.setString(parameter++, nodeId);
            }
            if (reason != null) {
                statement.setString(parameter++, reason);
            }
            if (userRef != null) {
                statement.setString(parameter++, userRef);
            }
            if (sourceNetwork != null) {
                statement.setString(parameter++, sourceNetwork);
            }
            if (cursor != null) {
                statement.setObject(parameter++, utc(cursor.occurredAt()));
                statement.setObject(parameter++, utc(cursor.occurredAt()));
                statement.setString(parameter++, cursor.eventId());
            }
            if (regexFilter == null) {
                statement.setInt(parameter, limit);
            }
            return readEvents(statement, regexFilter, limit, 0);
        }
    }

    private static List<Map<String, Object>> readEvents(final PreparedStatement statement)
            throws SQLException {
        return readEvents(statement, null, Integer.MAX_VALUE, 0);
    }

    private static List<Map<String, Object>> readEvents(
            final PreparedStatement statement,
            final EventRegexFilter regexFilter,
            final int limit,
            final int offset) throws SQLException {
        final List<Map<String, Object>> events = new ArrayList<>();
        int skipped = 0;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventId", resultSet.getString("event_id"));
                row.put("occurredAt",
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant());
                row.put("receivedAt",
                        resultSet.getObject("received_at", OffsetDateTime.class).toInstant());
                row.put("nodeId", resultSet.getString("node_id"));
                row.put("event", resultSet.getString("event_type"));
                row.put("result", resultSet.getString("result"));
                row.put("reason", resultSet.getString("reason"));
                row.put("userRef", resultSet.getString("user_ref"));
                row.put("sourceNetwork", resultSet.getString("source_network"));
                final long lockedUntil = resultSet.getLong("locked_until");
                row.put("lockedUntil",
                        resultSet.wasNull() ? null : Instant.ofEpochMilli(lockedUntil));
                if (regexFilter != null && !regexFilter.matches(row)) {
                    continue;
                }
                if (regexFilter != null && skipped++ < offset) {
                    continue;
                }
                events.add(row);
                if (events.size() == limit) {
                    break;
                }
            }
        }
        return events;
    }

    public List<Map<String, Object>> ingestHealth() throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT node_id, last_received_at, last_occurred_at, last_event_id,
                               accepted_count, duplicate_count, parse_failure_count,
                               clock_skew_count, agent_spool_bytes, agent_version
                        FROM dashboard_ingest_state ORDER BY node_id
                        """);
                ResultSet resultSet = statement.executeQuery()) {
            final List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("nodeId", resultSet.getString("node_id"));
                row.put("lastReceivedAt", instant(resultSet, "last_received_at"));
                row.put("lastOccurredAt", instant(resultSet, "last_occurred_at"));
                row.put("lastEventId", resultSet.getString("last_event_id"));
                row.put("acceptedCount", resultSet.getLong("accepted_count"));
                row.put("duplicateCount", resultSet.getLong("duplicate_count"));
                row.put("parseFailureCount", resultSet.getLong("parse_failure_count"));
                row.put("clockSkewCount", resultSet.getLong("clock_skew_count"));
                row.put("agentSpoolBytes", resultSet.getLong("agent_spool_bytes"));
                row.put("agentVersion", resultSet.getString("agent_version"));
                rows.add(row);
            }
            return rows;
        }
    }

    public long deleteOlderThan(final Instant cutoff) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM dashboard_event WHERE occurred_at < ?")) {
            statement.setObject(1, utc(cutoff));
            return statement.executeUpdate();
        }
    }

    public long deleteAggregatesOlderThan(final Instant cutoff) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM dashboard_hourly WHERE bucket_start < ?")) {
            statement.setObject(1, utc(cutoff));
            return statement.executeUpdate();
        }
    }

    public void recordAccess(
            final String actor,
            final String role,
            final String action,
            final String scope,
            final String result,
            final String remoteAddress) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO dashboard_access_audit (
                          occurred_at, actor, role_name, action_name, query_scope, result, remote_address
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, utc(Instant.now()));
            statement.setString(2, actor);
            statement.setString(3, role);
            statement.setString(4, action);
            statement.setString(5, scope);
            statement.setString(6, result);
            statement.setString(7, remoteAddress);
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // Access-audit failure must not expose data or take down the read-only UI.
        }
    }

    private static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(final ResultSet resultSet, final String column) throws SQLException {
        final OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @Override
    public void close() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // Shutdown is best effort; H2 will recover from its transaction log.
        }
    }
}
