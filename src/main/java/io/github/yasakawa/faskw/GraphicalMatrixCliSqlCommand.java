/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.postgresql.core.Parser;

/** Executes only CLI-generated writes. JDBC counts/RETURNING rows are retained until commit acknowledgement. */
public final class GraphicalMatrixCliSqlCommand {
    private GraphicalMatrixCliSqlCommand() { }

    public static void main(final String[] args) {
        try {
            if (args.length != 5) { throw new IllegalArgumentException(); }
            final byte[] input = System.in.readNBytes(128 * 1024 * 1024 + 1);
            if (input.length > 128 * 1024 * 1024) { throw new IllegalArgumentException(); }
            execute(args[0], args[1], args[2], args[3], changes(args[4]), new String(input, StandardCharsets.UTF_8));
        } catch (GraphicalMatrixAdminAuditClient.AuditAfterCommitException ex) {
            System.err.println("ERROR: DB committed; audit incomplete. Do not rerun blindly."); System.exit(74);
        } catch (DisplayException ex) {
            System.err.println("ERROR: DB committed, but result display failed. Inspect current state before retry."); System.exit(1);
        } catch (Exception ex) {
            // JDBC errors can contain the complete credential-bearing statement. Do not print them.
            System.err.println("ERROR: CLI database write failed (details withheld); inspect audit outcome and database availability");
            System.exit(1);
        }
    }

    static Map<GraphicalMatrixCliOperationReport.Field, String> changes(final String encoded) {
        final Map<GraphicalMatrixCliOperationReport.Field, String> values = new EnumMap<>(GraphicalMatrixCliOperationReport.Field.class);
        if (!encoded.isEmpty()) {
            for (String pair : encoded.split(",")) {
                final String[] fields = pair.split("=", 2);
                if (fields.length != 2 || values.put(GraphicalMatrixCliOperationReport.Field.valueOf(fields[0]), fields[1]) != null) {
                    throw new IllegalArgumentException("INVALID_CHANGE_METADATA");
                }
            }
        }
        return values;
    }

    static void execute(final String home, final String kind, final String stage, final String user,
            final Map<GraphicalMatrixCliOperationReport.Field, String> changes, final String sql) throws Exception {
        final var db = GraphicalMatrixDbConfig.load(home);
        Class.forName(db.getDriver());
        try (Connection c = DriverManager.getConnection(db.getUrl(), db.getUser(), db.getPassword())) {
            execute(c, db.getUrl().startsWith("jdbc:postgresql:"), kind, stage, user, changes, sql);
        }
    }

    static List<GraphicalMatrixCliOperationReport> execute(final Connection c, final boolean postgresql, final String kind, final String stage,
            final String user, final Map<GraphicalMatrixCliOperationReport.Field, String> changes, final String sql) throws Exception {
        if (!Set.of("enrollment", "webauthn", "schema", "csv", "targets").contains(kind)) { throw new IllegalArgumentException(); }
        c.setAutoCommit(false);
        try (var audit = GraphicalMatrixAdminAuditClient.transaction(c, stage)) {
            final List<GraphicalMatrixCliOperationReport> reports = new ArrayList<>();
            final List<String> display = new ArrayList<>();
            String csvTarget = null;
            Map<GraphicalMatrixCliOperationReport.Field, String> csvChanges = Map.of();
            long count = 0;
            // Use the driver's SQL parser for semicolons, quoted strings and PostgreSQL dollar-quoted DO blocks.
            for (var query : Parser.parseJdbcSql(sql, true, false, true, false, false)) {
                final String statement = query.nativeSql.strip();
                if (statement.isEmpty() || Set.of("BEGIN", "COMMIT").contains(statement.toUpperCase(Locale.ROOT))) { continue; }
                try (Statement st = c.createStatement()) {
                    final boolean result = st.execute(statement);
                    if (result) {
                        try (ResultSet rs = st.getResultSet()) {
                            final ResultSetMetaData metadata = rs.getMetaData();
                            if (kind.equals("csv") && metadata.getColumnLabel(1).equalsIgnoreCase("audit_csv_action")) {
                                if (csvTarget != null) { reports.add(rowReport(c, csvTarget, count > 0 ? 1 : 0, csvChanges)); }
                                if (!rs.next()) { throw new SQLException(); }
                                csvTarget = rs.getString("audit_target");
                                final String action = rs.getString("audit_csv_action");
                                final Map<GraphicalMatrixCliOperationReport.Field, String> next = new EnumMap<>(GraphicalMatrixCliOperationReport.Field.class);
                                next.putAll(changes);
                                next.put(GraphicalMatrixCliOperationReport.Field.CSV_ACTION, action);
                                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                    if (metadata.getColumnLabel(column).equalsIgnoreCase("audit_line")) {
                                        next.put(GraphicalMatrixCliOperationReport.Field.CSV_LINE, rs.getString(column));
                                    }
                                }
                                if (!action.equals("D")) {
                                    next.put(GraphicalMatrixCliOperationReport.Field.SEQUENCE_CHANGED, "true");
                                    next.put(GraphicalMatrixCliOperationReport.Field.INITIAL_SEQUENCE_CHANGED, "true");
                                    next.put(GraphicalMatrixCliOperationReport.Field.MFA_METHOD, rs.getString("audit_method"));
                                    next.put(GraphicalMatrixCliOperationReport.Field.FORCE_SEQUENCE_CHANGE, rs.getString("audit_force"));
                                }
                                csvChanges = Map.copyOf(next); count = 0;
                            } else if (kind.equals("targets")) {
                                while (rs.next()) { reports.add(GraphicalMatrixCliOperationReport.changed(rs.getString("audit_target"), 1, changes)); }
                            } else if (kind.equals("webauthn")) {
                                long rows = 0, credentials = 0;
                                while (rs.next()) {
                                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                                        final String name = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                                        if (Set.of("storage_rows_reset", "storage_rows_updated", "storage_rows_deleted").contains(name)) { rows += rs.getLong(i); }
                                        else if (Set.of("credentials_reset", "credentials_deleted").contains(name)) { credentials += rs.getLong(i); }
                                        else { throw new SQLException("UNEXPECTED_WRITE_RESULT"); }
                                        display.add(name + "=" + rs.getLong(i));
                                    }
                                }
                                final var fields = new EnumMap<GraphicalMatrixCliOperationReport.Field, String>(GraphicalMatrixCliOperationReport.Field.class);
                                fields.putAll(changes); fields.put(GraphicalMatrixCliOperationReport.Field.CREDENTIALS_DELETED, Long.toString(credentials));
                                reports.add(GraphicalMatrixCliOperationReport.changed(user, rows, fields));
                            }
                            // Existence-check SELECT/DO results are not evidence of a mutation.
                        }
                    } else if (!kind.equals("schema") && st.getLargeUpdateCount() >= 0) {
                        count += st.getLargeUpdateCount();
                    }
                }
            }
            if (kind.equals("csv")) {
                if (csvTarget != null) { reports.add(rowReport(c, csvTarget, count > 0 ? 1 : 0, csvChanges)); }
            } else if (kind.equals("enrollment")) { reports.add(rowReport(c, user, count, changes)); }
            else if (kind.equals("webauthn") && count > 0) { reports.add(rowReport(c, user, count, changes)); }
            audit.commit(reports);
            if (!display.isEmpty()) {
                GraphicalMatrixAdminAuditClient.send("MARK", "DISPLAY", "NOT_ATTEMPTED", List.of());
                display.forEach(System.out::println);
                if (System.out.checkError()) { throw new DisplayException(); }
            }
            return List.copyOf(reports);
        }
    }

    private static final class DisplayException extends java.io.IOException { }

    private static GraphicalMatrixCliOperationReport rowReport(final Connection c, final String user, final long count,
            final Map<GraphicalMatrixCliOperationReport.Field, String> supplied) throws SQLException {
        if (count == 0) {
            final var correlation = new EnumMap<GraphicalMatrixCliOperationReport.Field, String>(GraphicalMatrixCliOperationReport.Field.class);
            for (var field : List.of(GraphicalMatrixCliOperationReport.Field.CSV_SHA256, GraphicalMatrixCliOperationReport.Field.CSV_ACTION,
                    GraphicalMatrixCliOperationReport.Field.CSV_LINE)) {
                if (supplied.containsKey(field)) { correlation.put(field, supplied.get(field)); }
            }
            return GraphicalMatrixCliOperationReport.changed(user, 0, correlation);
        }
        final var fields = new EnumMap<GraphicalMatrixCliOperationReport.Field, String>(GraphicalMatrixCliOperationReport.Field.class);
        fields.putAll(supplied);
        // This is the same transaction as the counted write, not a post-commit guess about affected rows.
        try (PreparedStatement ps = c.prepareStatement("SELECT mfa_method,status,force_sequence_change,locked_until,"
                + "state_version,CASE WHEN totp_seed IS NULL OR totp_seed='' THEN 0 ELSE 1 END AS seed_set "
                + "FROM graphicalmatrix_enrollment WHERE user_id=?")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (Set.of("GraphicalMatrix", "TOTP", "WebAuthn").contains(rs.getString("mfa_method"))) {
                        fields.put(GraphicalMatrixCliOperationReport.Field.MFA_METHOD, rs.getString("mfa_method"));
                    }
                    if (Set.of("ACTIVE", "DISABLED").contains(rs.getString("status"))) {
                        fields.put(GraphicalMatrixCliOperationReport.Field.STATUS, rs.getString("status"));
                    }
                    fields.put(GraphicalMatrixCliOperationReport.Field.FORCE_SEQUENCE_CHANGE, rs.getInt("force_sequence_change") == 0 ? "0" : "1");
                    fields.put(GraphicalMatrixCliOperationReport.Field.LOCKED_UNTIL, Long.toString(rs.getLong("locked_until")));
                    fields.put(GraphicalMatrixCliOperationReport.Field.STATE_VERSION, Long.toString(rs.getLong("state_version")));
                    fields.put(GraphicalMatrixCliOperationReport.Field.SEED_SET, rs.getInt("seed_set") == 0 ? "false" : "true");
                }
            }
        }
        return GraphicalMatrixCliOperationReport.changed(user, count, fields);
    }
}
