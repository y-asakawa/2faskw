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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class GraphicalMatrixTotpSeedMigrationTool {
    private GraphicalMatrixTotpSeedMigrationTool() {
    }

    public static void main(final String[] args) {
        if (args.length != 2 || (!"plan".equals(args[0]) && !"apply".equals(args[0]))) {
            usage();
            System.exit(2);
        }

        final boolean apply = "apply".equals(args[0]);
        final String idpHome = args[1];
        try {
            final int errors = run(idpHome, apply);
            if (errors > 0) {
                System.exit(1);
            }
        } catch (Exception ex) {
            System.err.println("ERROR: " + safeDetail(ex));
            System.exit(1);
        }
    }

    private static int run(final String idpHome, final boolean apply) throws Exception {
        final GraphicalMatrixDbConfig dbConfig = GraphicalMatrixDbConfig.load(idpHome);
        final GraphicalMatrixTotpSeedStorage storage = GraphicalMatrixTotpSeedStorage.load(idpHome);
        final String targetMode = storage.mode();
        if ("unconfigured-hash".equals(targetMode)) {
            throw new IllegalStateException(
                "graphicalmatrix.totp.seed.storage=auto cannot inherit hash sequence storage; "
                + "set graphicalmatrix.totp.seed.storage to aes-gcm or keyword before migration.");
        }

        Class.forName(dbConfig.getDriver());
        try (Connection conn = DriverManager.getConnection(dbConfig.getUrl(),
                dbConfig.getUser(), dbConfig.getPassword())) {
            conn.setAutoCommit(false);

            final List<Row> rows = loadRows(conn);
            final List<Migration> migrations = new ArrayList<>();
            final Summary summary = new Summary(rows.size());

            for (final Row row : rows) {
                final String sourceMode = storage.storedMode(row.seed);
                if ("empty".equals(sourceMode)) {
                    summary.skippedEmpty++;
                    continue;
                }
                if (targetMode.equals(sourceMode)) {
                    summary.already++;
                    System.out.println("OK user=" + row.userId + " totp_seed_storage=" + sourceMode);
                    continue;
                }
                try {
                    final String plain = storage.decodeForMigration(row.seed);
                    final String encoded = storage.encode(plain);
                    migrations.add(new Migration(row.userId, sourceMode, targetMode, encoded,
                        row.seed, row.stateVersion));
                    summary.planned++;
                    System.out.println((apply ? "APPLY" : "PLAN") + " user=" + row.userId
                        + " from=" + sourceMode + " to=" + targetMode);
                } catch (Exception ex) {
                    summary.errors++;
                    System.out.println("ERROR user=" + row.userId
                        + " from=" + sourceMode + " to=" + targetMode
                        + " detail=" + safeDetail(ex));
                }
            }

            if (apply && summary.errors == 0) {
                applyMigrations(conn, migrations);
                summary.applied = migrations.size();
                conn.commit();
            } else {
                conn.rollback();
            }

            printSummary(apply, targetMode, summary);
            return summary.errors;
        }
    }

    private static void initSchema(final Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS graphicalmatrix_enrollment ("
                + "user_id VARCHAR(255) PRIMARY KEY,"
                + "sequence VARCHAR(1024) NOT NULL,"
                + "initial_sequence VARCHAR(1024) NOT NULL DEFAULT '',"
                + "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',"
                + "failed_count INT NOT NULL DEFAULT 0,"
                + "locked_until BIGINT NOT NULL DEFAULT 0,"
                + "mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix',"
                + "totp_seed VARCHAR(255),"
                + "totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED',"
                + "totp_registered_at BIGINT NOT NULL DEFAULT 0,"
                + "totp_registration_id VARCHAR(64),"
                + "totp_registration_expires_at BIGINT NOT NULL DEFAULT 0,"
                + "last_success_at BIGINT NOT NULL DEFAULT 0,"
                + "force_sequence_change INT NOT NULL DEFAULT 0,"
                + "state_version BIGINT NOT NULL DEFAULT 0,"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL"
                + ")");
            addColumnIfMissing(st, "totp_seed VARCHAR(255)");
            addColumnIfMissing(st, "totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED'");
            addColumnIfMissing(st, "totp_registered_at BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "totp_registration_id VARCHAR(64)");
            addColumnIfMissing(st, "totp_registration_expires_at BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "state_version BIGINT NOT NULL DEFAULT 0");
        }
    }

    private static void addColumnIfMissing(final Statement st, final String columnDefinition) throws Exception {
        st.execute("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS " + columnDefinition);
    }

    private static List<Row> loadRows(final Connection conn) throws Exception {
        final List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, totp_seed, state_version FROM graphicalmatrix_enrollment "
                + "WHERE totp_seed IS NOT NULL AND totp_seed <> '' ORDER BY user_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new Row(rs.getString("user_id"), rs.getString("totp_seed"),
                    rs.getLong("state_version")));
            }
        }
        return rows;
    }

    private static void applyMigrations(final Connection conn,
            final List<Migration> migrations) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE graphicalmatrix_enrollment SET totp_seed = ?, "
                + "totp_registration_id = NULL, totp_registration_expires_at = 0, "
                + "state_version = state_version + 1, updated_at = ? "
                + "WHERE user_id = ? AND state_version = ? AND totp_seed = ?")) {
            final long now = System.currentTimeMillis();
            for (final Migration migration : migrations) {
                ps.setString(1, migration.encodedSeed);
                ps.setLong(2, now);
                ps.setString(3, migration.userId);
                ps.setLong(4, migration.stateVersion);
                ps.setString(5, migration.originalSeed);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("Enrollment changed during TOTP seed migration: user="
                        + migration.userId);
                }
            }
        }
    }

    private static void printSummary(final boolean apply, final String targetMode,
            final Summary summary) {
        System.out.println("summary.mode=" + (apply ? "apply" : "dry-run"));
        System.out.println("summary.target_totp_seed_storage=" + targetMode);
        System.out.println("summary.total=" + summary.total);
        System.out.println("summary.already=" + summary.already);
        System.out.println("summary.planned=" + summary.planned);
        System.out.println("summary.applied=" + summary.applied);
        System.out.println("summary.skipped_empty=" + summary.skippedEmpty);
        System.out.println("summary.errors=" + summary.errors);
    }

    private static String safeDetail(final Exception ex) {
        final String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  GraphicalMatrixTotpSeedMigrationTool plan IDP_HOME");
        System.err.println("  GraphicalMatrixTotpSeedMigrationTool apply IDP_HOME");
    }

    private static final class Row {
        private final String userId;
        private final String seed;
        private final long stateVersion;

        private Row(final String userId, final String seed, final long stateVersion) {
            this.userId = userId;
            this.seed = seed;
            this.stateVersion = stateVersion;
        }
    }

    private static final class Migration {
        private final String userId;
        private final String encodedSeed;
        private final String originalSeed;
        private final long stateVersion;

        private Migration(final String userId, final String sourceMode,
                final String targetMode, final String encodedSeed,
                final String originalSeed, final long stateVersion) {
            this.userId = userId;
            this.encodedSeed = encodedSeed;
            this.originalSeed = originalSeed;
            this.stateVersion = stateVersion;
        }
    }

    private static final class Summary {
        private final int total;
        private int already;
        private int planned;
        private int applied;
        private int skippedEmpty;
        private int errors;

        private Summary(final int total) {
            this.total = total;
        }
    }
}
