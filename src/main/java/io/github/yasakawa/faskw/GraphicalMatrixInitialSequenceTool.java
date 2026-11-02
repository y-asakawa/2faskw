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

import java.sql.*;
import java.util.List;

/** Transactional CLI writes: plaintext input arrives only on stdin, never in SQL arguments. */
public final class GraphicalMatrixInitialSequenceTool {
    private GraphicalMatrixInitialSequenceTool() { }

    public static void main(final String[] args) {
        if (args.length != 3) { throw new IllegalArgumentException("Expected operation IDP_HOME USER"); }
        try { run(args[0], args[1], args[2]); }
        catch (Exception ex) { System.err.println("Enrollment update failed: " + ex.getMessage()); System.exit(1); }
    }

    static void run(final String operation, final String home, final String user) throws Exception {
        if (!user.matches("[A-Za-z0-9._@-]+") || !List.of("current", "initial", "reset", "allow-change-off", "require-change").contains(operation)) {
            throw new IllegalArgumentException("Invalid enrollment operation");
        }
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(home);
        final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(home);
        final GraphicalMatrixDbConfig db = GraphicalMatrixDbConfig.load(home);
        final List<String> selected = List.of("reset", "allow-change-off", "require-change").contains(operation) ? List.of()
            : config.resolveSequenceToGraphicals(GraphicalMatrixSupport.csv(GraphicalMatrixSequenceTool.readSequenceInput(System.in)));
        if (!List.of("reset", "allow-change-off", "require-change").contains(operation)) { config.validateSequence(selected); }
        final String encoded = selected.isEmpty() ? "" : "initial".equals(operation)
            ? String.join(",", config.normalizeInitialSequence(selected))
            : storage.encode(selected, config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed());
        Class.forName(db.getDriver());
        try (Connection c = DriverManager.getConnection(db.getUrl(), db.getUser(), db.getPassword())) {
            c.setAutoCommit(false);
            try {
                if (db.isAutoInit()) { GraphicalMatrixSequenceMigrationTool.initSchema(c); }
                String initial = null;
                String current = null;
                long version = -1;
                int force = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT sequence, initial_sequence, state_version, force_sequence_change FROM graphicalmatrix_enrollment WHERE user_id=? FOR UPDATE")) {
                    ps.setString(1, user);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            initial = rs.getString("initial_sequence"); current = rs.getString("sequence");
                            version = rs.getLong("state_version"); force = rs.getInt("force_sequence_change");
                        }
                    }
                }
                final long now = System.currentTimeMillis();
                if (version < 0) {
                    if (!"current".equals(operation)) { throw new IllegalStateException("USER_NOT_FOUND"); }
                    final String bootstrap = String.join(",", config.normalizeInitialSequence(selected));
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO graphicalmatrix_enrollment (user_id,sequence,initial_sequence,force_sequence_change,status,created_at,updated_at,state_version) VALUES (?,?,?,1,'ACTIVE',?,?,1)")) {
                        ps.setString(1, user); ps.setString(2, encoded); ps.setString(3, bootstrap);
                        ps.setLong(4, now); ps.setLong(5, now); ps.executeUpdate();
                    }
                } else {
                    Math.addExact(version, 1L);
                    final String sql;
                    final String nextInitial;
                    final String nextCurrent;
                    if ("reset".equals(operation)) {
                        if (initial == null || initial.isBlank()) { throw new IllegalStateException("INITIAL_SEQUENCE_REQUIRED"); }
                        nextCurrent = storage.resetFromInitial(initial, config); nextInitial = initial; force = 1;
                        sql = "mfa_method='GraphicalMatrix', status='ACTIVE', failed_count=0, locked_until=0, totp_seed=NULL, totp_status='UNREGISTERED', totp_registered_at=0, ";
                    } else if ("initial".equals(operation)) {
                        nextCurrent = current; nextInitial = encoded;
                        if (storage.matches(current, selected, config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed())) { force = 1; }
                        sql = "";
                    } else if ("allow-change-off".equals(operation) || "require-change".equals(operation)) {
                        if ("allow-change-off".equals(operation) && ("hash".equals(storage.storedMode(initial))
                                || storage.initialMatchesCurrent(current, initial, config))) {
                            throw new IllegalStateException("INITIAL_CHANGE_REQUIRED: replace the bootstrap credential before disabling forced change");
                        }
                        nextCurrent = current; nextInitial = storage.preserveInitial(initial);
                        force = "require-change".equals(operation) ? 1 : 0; sql = "";
                    } else {
                        nextCurrent = encoded; nextInitial = storage.preserveInitial(initial);
                        if (storage.initialMatchesCurrent(nextCurrent, nextInitial, config)) { force = 1; }
                        sql = "status='ACTIVE', failed_count=0, locked_until=0, ";
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE graphicalmatrix_enrollment SET " + sql
                            + "sequence=?, initial_sequence=?, force_sequence_change=?, totp_registration_id=NULL, totp_registration_expires_at=0, state_version=state_version+1, updated_at=? WHERE user_id=? AND state_version=?")) {
                        ps.setString(1, nextCurrent); ps.setString(2, nextInitial); ps.setInt(3, force);
                        ps.setLong(4, now); ps.setString(5, user); ps.setLong(6, version);
                        if (ps.executeUpdate() != 1) { throw new IllegalStateException("ENROLLMENT_CHANGED"); }
                    }
                }
                c.commit();
            } catch (Exception ex) { c.rollback(); throw ex; }
        }
    }
}
