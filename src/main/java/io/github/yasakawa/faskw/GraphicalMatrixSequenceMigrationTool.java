package io.github.yasakawa.faskw;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class GraphicalMatrixSequenceMigrationTool {
    private GraphicalMatrixSequenceMigrationTool() {
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
            System.err.println("ERROR: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static int run(final String idpHome, final boolean apply) throws Exception {
        final GraphicalMatrixDbConfig dbConfig = GraphicalMatrixDbConfig.load(idpHome);
        final GraphicalMatrixConfig graphicalConfig = GraphicalMatrixConfig.load(idpHome);
        final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(idpHome);
        final String targetMode = storage.mode();

        Class.forName(dbConfig.getDriver());
        try (Connection conn = DriverManager.getConnection(dbConfig.getUrl(),
                dbConfig.getUser(), dbConfig.getPassword())) {
            conn.setAutoCommit(false);
            initSchema(conn);

            final List<Row> rows = loadRows(conn);
            final List<Migration> migrations = new ArrayList<>();
            final Summary summary = new Summary(rows.size());

            for (final Row row : rows) {
                final String sourceMode = storage.storedMode(row.sequence);
                if ("empty".equals(sourceMode)) {
                    summary.skippedEmpty++;
                    System.out.println("SKIP user=" + row.userId + " reason=empty");
                    continue;
                }
                if (targetMode.equals(sourceMode)) {
                    summary.already++;
                    System.out.println("OK user=" + row.userId + " storage=" + sourceMode);
                    continue;
                }
                if (!storage.recoverable(row.sequence)) {
                    summary.skippedHash++;
                    System.out.println("SKIP user=" + row.userId
                        + " from=" + sourceMode + " to=" + targetMode
                        + " reason=not_recoverable");
                    continue;
                }

                try {
                    final List<String> decoded = storage.displayTokens(row.sequence);
                    graphicalConfig.validateSequence(decoded);
                    final String encoded = storage.encode(decoded,
                        graphicalConfig.isOrderedSelectionRequired(),
                        graphicalConfig.isDuplicateSelectionsAllowed());
                    migrations.add(new Migration(row.userId, sourceMode, targetMode,
                        decoded.size(), encoded));
                    summary.planned++;
                    System.out.println((apply ? "APPLY" : "PLAN") + " user=" + row.userId
                        + " from=" + sourceMode + " to=" + targetMode
                        + " count=" + decoded.size());
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
                + "last_success_at BIGINT NOT NULL DEFAULT 0,"
                + "force_sequence_change INT NOT NULL DEFAULT 0,"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL"
                + ")");
            addColumnIfMissing(st, "last_success_at BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix'");
            addColumnIfMissing(st, "totp_seed VARCHAR(255)");
            addColumnIfMissing(st, "totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED'");
            addColumnIfMissing(st, "totp_registered_at BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "force_sequence_change INT NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "initial_sequence VARCHAR(1024) NOT NULL DEFAULT ''");
            st.executeUpdate("UPDATE graphicalmatrix_enrollment SET initial_sequence = sequence "
                + "WHERE initial_sequence IS NULL OR initial_sequence = ''");
        }
    }

    private static void addColumnIfMissing(final Statement st, final String columnDefinition) {
        try {
            st.execute("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS "
                + columnDefinition);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to ensure DB column: "
                + columnDefinition, ex);
        }
    }

    private static List<Row> loadRows(final Connection conn) throws Exception {
        final List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, sequence FROM graphicalmatrix_enrollment ORDER BY user_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new Row(rs.getString("user_id"), rs.getString("sequence")));
            }
        }
        return rows;
    }

    private static void applyMigrations(final Connection conn,
            final List<Migration> migrations) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE graphicalmatrix_enrollment SET sequence = ?, updated_at = ? WHERE user_id = ?")) {
            final long now = System.currentTimeMillis();
            for (final Migration migration : migrations) {
                ps.setString(1, migration.encodedSequence);
                ps.setLong(2, now);
                ps.setString(3, migration.userId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void printSummary(final boolean apply, final String targetMode,
            final Summary summary) {
        System.out.println("summary.mode=" + (apply ? "apply" : "dry-run"));
        System.out.println("summary.target_storage=" + targetMode);
        System.out.println("summary.total=" + summary.total);
        System.out.println("summary.already=" + summary.already);
        System.out.println("summary.planned=" + summary.planned);
        System.out.println("summary.applied=" + summary.applied);
        System.out.println("summary.skipped_hash=" + summary.skippedHash);
        System.out.println("summary.skipped_empty=" + summary.skippedEmpty);
        System.out.println("summary.errors=" + summary.errors);
    }

    private static String safeDetail(final Exception ex) {
        final String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  GraphicalMatrixSequenceMigrationTool plan IDP_HOME");
        System.err.println("  GraphicalMatrixSequenceMigrationTool apply IDP_HOME");
    }

    private static final class Row {
        private final String userId;
        private final String sequence;

        private Row(final String userId, final String sequence) {
            this.userId = userId;
            this.sequence = sequence;
        }
    }

    private static final class Migration {
        private final String userId;
        private final String sourceMode;
        private final String targetMode;
        private final int count;
        private final String encodedSequence;

        private Migration(final String userId, final String sourceMode,
                final String targetMode, final int count, final String encodedSequence) {
            this.userId = userId;
            this.sourceMode = sourceMode;
            this.targetMode = targetMode;
            this.count = count;
            this.encodedSequence = encodedSequence;
        }
    }

    private static final class Summary {
        private final int total;
        private int already;
        private int planned;
        private int applied;
        private int skippedHash;
        private int skippedEmpty;
        private int errors;

        private Summary(final int total) {
            this.total = total;
        }
    }
}
