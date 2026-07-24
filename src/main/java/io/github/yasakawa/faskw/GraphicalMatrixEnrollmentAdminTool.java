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
import java.util.List;

public final class GraphicalMatrixEnrollmentAdminTool {
    private GraphicalMatrixEnrollmentAdminTool() {
    }

    public static void main(final String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: GraphicalMatrixEnrollmentAdminTool IDP_HOME [USER]");
            System.exit(2);
        }
        try {
            list(args[0], args.length == 2 ? args[1] : "");
        } catch (Exception ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void list(final String idpHome, final String user) throws Exception {
        final GraphicalMatrixDbConfig db = GraphicalMatrixDbConfig.load(idpHome);
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome);
        final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(idpHome);
        Class.forName(db.getDriver());

        final String sql = "SELECT user_id, mfa_method, force_sequence_change, initial_sequence, "
            + "sequence, status, failed_count, locked_until, totp_status, state_version "
            + "FROM graphicalmatrix_enrollment "
            + (user.isEmpty() ? "ORDER BY user_id" : "WHERE user_id = ? ORDER BY user_id");
        try (Connection connection = DriverManager.getConnection(db.getUrl(), db.getUser(), db.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!user.isEmpty()) {
                statement.setString(1, user);
            }
            System.out.println("user_id\tmfa_method\tforce_sequence_change\tinitial_sequence"
                + "\tsequence\tstatus\tfailed_count\tlocked_until\ttotp_status\tstate_version");
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    final String initial = initialDisplay(config, rs.getString("initial_sequence"));
                    final String current = display(storage, rs.getString("sequence"));
                    System.out.println(cell(rs.getString("user_id"))
                        + "\t" + cell(rs.getString("mfa_method"))
                        + "\t" + rs.getInt("force_sequence_change")
                        + "\t" + cell(initial)
                        + "\t" + cell(current)
                        + "\t" + cell(rs.getString("status"))
                        + "\t" + rs.getInt("failed_count")
                        + "\t" + rs.getLong("locked_until")
                        + "\t" + cell(rs.getString("totp_status"))
                        + "\t" + rs.getLong("state_version"));
                }
            }
        }
    }

    private static String initialDisplay(final GraphicalMatrixConfig config, final String stored) {
        final List<String> decoded = GraphicalMatrixSupport.csv(stored);
        return decoded.isEmpty() ? "" : String.join(",", config.normalizeInitialSequence(decoded));
    }

    private static String display(final GraphicalMatrixSequenceStorage storage, final String stored) {
        return storageMarker(storage, stored);
    }

    private static String storageMarker(final GraphicalMatrixSequenceStorage storage, final String stored) {
        final String mode = storage.storedMode(stored);
        return "empty".equals(mode) ? "" : "<" + mode + ">";
    }

    private static String cell(final String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
