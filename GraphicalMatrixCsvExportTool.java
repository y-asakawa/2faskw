package io.github.yasakawa.faskw.graphicalmatrix;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class GraphicalMatrixCsvExportTool {
    private static final String HEADER =
        "action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence";

    private GraphicalMatrixCsvExportTool() {
    }

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: GraphicalMatrixCsvExportTool IDP_HOME");
            System.exit(2);
        }

        try {
            final List<String> lines = export(args[0]);
            System.out.println(HEADER);
            for (final String line : lines) {
                System.out.println(line);
            }
            System.err.println("CSV export completed: rows=" + lines.size());
        } catch (Exception ex) {
            System.err.println("ERROR: " + safeDetail(ex));
            System.exit(1);
        }
    }

    private static List<String> export(final String idpHome) throws Exception {
        final GraphicalMatrixDbConfig dbConfig = GraphicalMatrixDbConfig.load(idpHome);
        final GraphicalMatrixConfig graphicalConfig = GraphicalMatrixConfig.load(idpHome);
        final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(idpHome);
        final List<String> lines = new ArrayList<>();

        Class.forName(dbConfig.getDriver());
        try (Connection conn = DriverManager.getConnection(dbConfig.getUrl(),
                dbConfig.getUser(), dbConfig.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, mfa_method, force_sequence_change, initial_sequence, sequence "
                + "FROM graphicalmatrix_enrollment ORDER BY user_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final String userId = value(rs.getString("user_id"));
                final String method = value(rs.getString("mfa_method"));
                final String initialSequence = value(rs.getString("initial_sequence"));
                final String storedSequence = value(rs.getString("sequence"));
                final String storedMode = storage.storedMode(storedSequence);

                if (!storage.recoverable(storedSequence)) {
                    throw new IllegalStateException("User " + userId
                        + " has non-recoverable hash sequence; portable CSV export is not possible.");
                }

                final List<String> decoded = storage.displayTokens(storedSequence);
                graphicalConfig.validateSequence(decoded);
                graphicalConfig.normalizeInitialSequence(GraphicalMatrixSupport.csv(initialSequence));

                lines.add(csv(
                    "A",
                    userId,
                    method,
                    rs.getInt("force_sequence_change") == 0 ? "off" : "on",
                    initialSequence,
                    String.join(",", decoded)
                ));
                if ("empty".equals(storedMode)) {
                    throw new IllegalStateException("User " + userId + " has empty sequence.");
                }
            }
        }
        return lines;
    }

    private static String csv(final String... fields) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            final String value = value(fields[i]);
            final boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
            if (quote) {
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                out.append(value);
            }
        }
        return out.toString();
    }

    private static String value(final String value) {
        return value != null ? value.trim() : "";
    }

    private static String safeDetail(final Exception ex) {
        final String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
