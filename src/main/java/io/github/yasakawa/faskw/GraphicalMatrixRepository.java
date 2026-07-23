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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GraphicalMatrixRepository {
    private static final AtomicBoolean SCHEMA_INITIALIZED = new AtomicBoolean(false);

    private final GraphicalMatrixDbConfig dbConfig;
    private final GraphicalMatrixSequenceStorage sequenceStorage;
    private final GraphicalMatrixTotpSeedStorage totpSeedStorage;
    private final GraphicalMatrixLdapEnrollmentStore ldapStore;

    public GraphicalMatrixRepository(final String idpHome) {
        this.sequenceStorage = GraphicalMatrixSequenceStorage.load(idpHome);
        this.totpSeedStorage = GraphicalMatrixTotpSeedStorage.load(idpHome);
        if (GraphicalMatrixSaveDataConfig.load(idpHome).isLdap()) {
            this.dbConfig = null;
            this.ldapStore = new GraphicalMatrixLdapEnrollmentStore(idpHome, sequenceStorage, totpSeedStorage);
        } else {
            this.dbConfig = GraphicalMatrixDbConfig.load(idpHome);
            this.ldapStore = null;
        }
    }

    public GraphicalMatrixEnrollment findEnrollment(final String user) throws Exception {
        if (ldapStore != null) {
            return ldapStore.findEnrollment(user);
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sequence, status, failed_count, locked_until, force_sequence_change, state_version "
                    + "FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new GraphicalMatrixEnrollment(
                            rs.getString("sequence"),
                            rs.getString("status"),
                            rs.getInt("failed_count"),
                            rs.getLong("locked_until"),
                            rs.getInt("force_sequence_change") != 0,
                            rs.getLong("state_version")
                        );
                    }
                }
            }
        }
        return null;
    }

    public String findMfaMethod(final String user) throws Exception {
        final GraphicalMatrixMfaSettings settings = findMfaSettings(user);
        return settings != null ? settings.getMethod() : null;
    }

    public GraphicalMatrixMfaSettings findMfaSettings(final String user) throws Exception {
        if (ldapStore != null) {
            return ldapStore.findMfaSettings(user);
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT mfa_method, totp_status, totp_seed "
                    + "FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        final String seed = rs.getString("totp_seed");
                        return new GraphicalMatrixMfaSettings(
                            rs.getString("mfa_method"),
                            rs.getString("totp_status"),
                            seed != null && !seed.trim().isEmpty()
                        );
                    }
                }
            }
        }
        return null;
    }

    public String prepareTotpRegistration(final String user, final long now) throws Exception {
        if (ldapStore != null) {
            return ldapStore.prepareTotpRegistration(user, now);
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            c.setAutoCommit(false);
            try {
                final String seed = prepareTotpRegistrationInTransaction(c, user, now);
                c.commit();
                return seed;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    private String prepareTotpRegistrationInTransaction(final Connection c, final String user,
            final long now) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT mfa_method, status, locked_until, totp_seed, totp_status "
                + "FROM graphicalmatrix_enrollment WHERE user_id = ? FOR UPDATE")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                if (!"ACTIVE".equals(rs.getString("status"))) {
                    return null;
                }
                if (rs.getLong("locked_until") > now) {
                    return null;
                }
                final String method = normalizeMethod(rs.getString("mfa_method"));
                if (!"TOTP".equals(method)) {
                    return null;
                }

                final String status = trim(rs.getString("totp_status"));
                final String currentSeed = trim(rs.getString("totp_seed"));
                if ("ACTIVE".equalsIgnoreCase(status) && !currentSeed.isEmpty()) {
                    return null;
                }
                if ("PENDING".equalsIgnoreCase(status) && !currentSeed.isEmpty()) {
                    return totpSeedStorage.decode(currentSeed);
                }

                final String seed = GraphicalMatrixTotpSupport.newBase32Seed();
                final String storedSeed = totpSeedStorage.encode(seed);
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET totp_seed = ?, totp_status = 'PENDING', updated_at = ? "
                        + "WHERE user_id = ?")) {
                    up.setString(1, storedSeed);
                    up.setLong(2, now);
                    up.setString(3, user);
                    up.executeUpdate();
                }
                return seed;
            }
        }
    }

    public GraphicalMatrixVerifyResult verifyAndActivateTotp(final String user, final String code,
            final long now) {
        if (ldapStore != null) {
            try {
                return ldapStore.verifyAndActivateTotp(user, code, now);
            } catch (Exception ex) {
                return GraphicalMatrixVerifyResult.dbError(ex.getClass().getSimpleName());
            }
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            c.setAutoCommit(false);
            try {
                final GraphicalMatrixVerifyResult result = verifyAndActivateTotpInTransaction(c, user, code, now);
                c.commit();
                return result;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            return GraphicalMatrixVerifyResult.dbError(ex.getClass().getSimpleName());
        }
    }

    private GraphicalMatrixVerifyResult verifyAndActivateTotpInTransaction(final Connection c,
            final String user, final String code, final long now) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT mfa_method, status, locked_until, totp_seed, totp_status "
                + "FROM graphicalmatrix_enrollment WHERE user_id = ? FOR UPDATE")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return GraphicalMatrixVerifyResult.enrollRequired("missing_enrollment");
                }
                if (!"ACTIVE".equals(rs.getString("status"))) {
                    return GraphicalMatrixVerifyResult.enrollRequired("inactive_enrollment");
                }
                final long lockedUntil = rs.getLong("locked_until");
                if (lockedUntil > now) {
                    return GraphicalMatrixVerifyResult.locked(
                        "locked_until=" + lockedUntil, lockedUntil);
                }
                if (!"TOTP".equals(normalizeMethod(rs.getString("mfa_method")))) {
                    return GraphicalMatrixVerifyResult.enrollRequired("not_totp_method");
                }

                final String storedSeed = trim(rs.getString("totp_seed"));
                final String status = trim(rs.getString("totp_status"));
                if (storedSeed.isEmpty() || !"PENDING".equalsIgnoreCase(status)) {
                    return GraphicalMatrixVerifyResult.enrollRequired("totp_not_pending");
                }
                final String seed = totpSeedStorage.decode(storedSeed);

                if (!GraphicalMatrixTotpSupport.verify(seed, code, now, 1)) {
                    return GraphicalMatrixVerifyResult.failed("totp_registration_code_mismatch");
                }

                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET totp_status = 'ACTIVE', totp_registered_at = ?, "
                        + "last_success_at = ?, updated_at = ? "
                        + "WHERE user_id = ?")) {
                    up.setLong(1, now);
                    up.setLong(2, now);
                    up.setLong(3, now);
                    up.setString(4, user);
                    up.executeUpdate();
                }
                return GraphicalMatrixVerifyResult.success("totp_registered");
            }
        }
    }

    public GraphicalMatrixVerifyResult verify(final String user, final List<String> selected,
            final List<String> displayOrder, final long now,
            final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed) {
        if (ldapStore != null) {
            try {
                return ldapStore.verify(user, selected, displayOrder, now, lockoutPolicy,
                    orderedSelectionRequired, duplicateSelectionsAllowed);
            } catch (Exception ex) {
                return GraphicalMatrixVerifyResult.dbError(ex.getClass().getSimpleName());
            }
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            c.setAutoCommit(false);
            try {
                final GraphicalMatrixVerifyResult result = verifyInTransaction(
                    c, user, selected, displayOrder, now, lockoutPolicy,
                    orderedSelectionRequired, duplicateSelectionsAllowed);
                c.commit();
                return result;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            return GraphicalMatrixVerifyResult.dbError(ex.getClass().getSimpleName());
        }
    }

    public GraphicalMatrixVerifyResult verifyForSequenceChange(final String user, final List<String> selected,
            final List<String> displayOrder, final long now,
            final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed) {
        if (ldapStore != null) {
            try {
                return ldapStore.verifyForSequenceChange(user, selected, displayOrder, now,
                    lockoutPolicy, orderedSelectionRequired, duplicateSelectionsAllowed);
            } catch (Exception ex) {
                return GraphicalMatrixVerifyResult.dbError(ex.getClass().getSimpleName());
            }
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            c.setAutoCommit(false);
            try {
                final GraphicalMatrixVerifyResult result = verifyForSequenceChangeInTransaction(
                    c, user, selected, displayOrder, now, lockoutPolicy,
                    orderedSelectionRequired, duplicateSelectionsAllowed);
                c.commit();
                return result;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            return GraphicalMatrixVerifyResult.dbError(ex.getClass().getSimpleName());
        }
    }

    public boolean updateSequence(final String user, final List<String> sequence, final long now,
            final long expectedStateVersion,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed) throws Exception {
        final String storedSequence = sequenceStorage.encode(
            sequence, orderedSelectionRequired, duplicateSelectionsAllowed);
        if (ldapStore != null) {
            return ldapStore.updateSequence(user, storedSequence, now, expectedStateVersion);
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE graphicalmatrix_enrollment "
                    + "SET sequence = ?, force_sequence_change = 0, updated_at = ?, "
                    + "state_version = state_version + 1 "
                    + "WHERE user_id = ? AND status = 'ACTIVE' "
                    + "AND locked_until <= ? "
                    + "AND state_version = ?")) {
                ps.setString(1, storedSequence);
                ps.setLong(2, now);
                ps.setString(3, user);
                ps.setLong(4, now);
                ps.setLong(5, expectedStateVersion);
                return ps.executeUpdate() == 1;
            }
        }
    }

    public boolean updateMfaMethod(final String user, final String method, final long now) throws Exception {
        return updateMfaMethod(user, method, now, null);
    }

    public boolean updateMfaMethodIfCurrent(final String user, final String method, final long now,
            final long expectedStateVersion) throws Exception {
        return updateMfaMethod(user, method, now, Long.valueOf(expectedStateVersion));
    }

    private boolean updateMfaMethod(final String user, final String method, final long now,
            final Long expectedStateVersion) throws Exception {
        final String normalized = normalizeMethod(method);
        if (!"GRAPHICALMATRIX".equals(normalized) && !"TOTP".equals(normalized)
                && !"WEBAUTHN".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported MFA method: " + method);
        }

        if (ldapStore != null) {
            return expectedStateVersion == null
                ? ldapStore.updateMfaMethod(user, method, now)
                : ldapStore.updateMfaMethodIfCurrent(user, method, now, expectedStateVersion.longValue());
        }

        try (Connection c = db()) {
            initDbIfEnabled(c);
            final String statePredicate = expectedStateVersion == null ? "" : " AND state_version = ?";
            if ("TOTP".equals(normalized)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET mfa_method = 'TOTP', totp_seed = NULL, "
                        + "totp_status = 'UNREGISTERED', totp_registered_at = 0, "
                        + "failed_count = 0, locked_until = 0, "
                        + "state_version = state_version + 1, updated_at = ? "
                        + "WHERE user_id = ? AND status = 'ACTIVE' "
                        + "AND locked_until <= ?" + statePredicate)) {
                    ps.setLong(1, now);
                    ps.setString(2, user);
                    ps.setLong(3, now);
                    if (expectedStateVersion != null) {
                        ps.setLong(4, expectedStateVersion.longValue());
                    }
                    return ps.executeUpdate() == 1;
                }
            }

            if ("WEBAUTHN".equals(normalized)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET mfa_method = ?, failed_count = 0, locked_until = 0, "
                        + "state_version = state_version + 1, updated_at = ? "
                        + "WHERE user_id = ? AND status = 'ACTIVE' "
                        + "AND locked_until <= ?" + statePredicate)) {
                    ps.setString(1, "WebAuthn");
                    ps.setLong(2, now);
                    ps.setString(3, user);
                    ps.setLong(4, now);
                    if (expectedStateVersion != null) {
                        ps.setLong(5, expectedStateVersion.longValue());
                    }
                    return ps.executeUpdate() == 1;
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE graphicalmatrix_enrollment "
                    + "SET mfa_method = 'GraphicalMatrix', failed_count = 0, locked_until = 0, "
                    + "state_version = state_version + 1, updated_at = ? "
                    + "WHERE user_id = ? AND status = 'ACTIVE' "
                    + "AND locked_until <= ?" + statePredicate)) {
                ps.setLong(1, now);
                ps.setString(2, user);
                ps.setLong(3, now);
                if (expectedStateVersion != null) {
                    ps.setLong(4, expectedStateVersion.longValue());
                }
                return ps.executeUpdate() == 1;
            }
        }
    }

    public boolean isForceSequenceChangeRequired(final String user) throws Exception {
        if (ldapStore != null) {
            return ldapStore.isForceSequenceChangeRequired(user);
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT force_sequence_change FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt("force_sequence_change") != 0;
                }
            }
        }
    }

    public String findActiveTotpSeed(final String user) throws Exception {
        if (ldapStore != null) {
            return ldapStore.findActiveTotpSeed(user);
        }
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT totp_seed FROM graphicalmatrix_enrollment "
                    + "WHERE user_id = ? AND status = 'ACTIVE' "
                    + "AND UPPER(mfa_method) IN ('TOTP', 'MFA:TOTP') "
                    + "AND totp_status = 'ACTIVE' "
                    + "AND totp_seed IS NOT NULL AND totp_seed <> ''")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return totpSeedStorage.decode(rs.getString("totp_seed"));
                    }
                }
            }
        }
        return "";
    }

    public boolean sequenceUsable(final String sequence, final GraphicalMatrixConfig config) {
        return sequenceStorage.usable(sequence, config);
    }

    public int sequenceCount(final String sequence) {
        return sequenceStorage.count(sequence);
    }

    public boolean sameSequence(final String storedSequence, final List<String> selected,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed) {
        return sequenceStorage.matches(
            storedSequence, selected, orderedSelectionRequired, duplicateSelectionsAllowed);
    }

    private GraphicalMatrixVerifyResult verifyInTransaction(final Connection c, final String user,
            final List<String> selected, final List<String> displayOrder, final long now,
            final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sequence, status, failed_count, locked_until "
                + "FROM graphicalmatrix_enrollment WHERE user_id = ? FOR UPDATE")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return GraphicalMatrixVerifyResult.enrollRequired("missing_enrollment");
                }

                final String sequence = rs.getString("sequence");
                final String status = rs.getString("status");
                final int failedCount = rs.getInt("failed_count");
                final long lockedUntil = rs.getLong("locked_until");

                if (sequence == null || sequence.trim().isEmpty() || !"ACTIVE".equals(status)) {
                    return GraphicalMatrixVerifyResult.enrollRequired("inactive_or_empty_sequence");
                }
                if (!sequenceStorage.acceptedForRuntime(sequence)) {
                    return GraphicalMatrixVerifyResult.enrollRequired(
                        "sequence_storage_migration_required");
                }
                if (lockedUntil > now) {
                    return GraphicalMatrixVerifyResult.locked(
                        "locked_until=" + lockedUntil, lockedUntil);
                }

                final int expectedCount = sequenceStorage.count(sequence);
                final Set<String> unique = new HashSet<>(selected);
                final boolean selectedShapeOk =
                    selected.size() == expectedCount
                    && (duplicateSelectionsAllowed || unique.size() == selected.size())
                    && displayOrder.containsAll(selected);

                if (selectedShapeOk && sequenceStorage.matches(sequence, selected, orderedSelectionRequired,
                        duplicateSelectionsAllowed)) {
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE graphicalmatrix_enrollment "
                            + "SET failed_count = 0, locked_until = 0, last_success_at = ?, updated_at = ? "
                            + "WHERE user_id = ?")) {
                        up.setLong(1, now);
                        up.setLong(2, now);
                        up.setString(3, user);
                        up.executeUpdate();
                    }
                    return GraphicalMatrixVerifyResult.success();
                }

                final int failed = incrementFailureCount(failedCount);
                final GraphicalMatrixLockoutPolicy.LockDecision lockDecision =
                    lockoutPolicy.afterFailure(failed);
                final long newLockedUntil = lockDecision.isLocked()
                    ? now + lockDecision.getLockMillis()
                    : 0L;
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET failed_count = ?, locked_until = ?, updated_at = ? "
                        + "WHERE user_id = ?")) {
                    up.setInt(1, failed);
                    up.setLong(2, newLockedUntil);
                    up.setLong(3, now);
                    up.setString(4, user);
                    up.executeUpdate();
                }

                final String detail = "failed_count=" + failed + ",selected_count=" + selected.size()
                    + ",order_mode=" + (orderedSelectionRequired ? "ordered" : "unordered")
                    + ",lock_level=" + lockDecision.getLevel().getAuditValue()
                    + ",lock_seconds=" + lockDecision.getLockSeconds()
                    + ",locked_until=" + newLockedUntil;
                return lockDecision.isLocked()
                    ? GraphicalMatrixVerifyResult.locked(detail, newLockedUntil)
                    : GraphicalMatrixVerifyResult.failed(detail);
            }
        }
    }

    private GraphicalMatrixVerifyResult verifyForSequenceChangeInTransaction(final Connection c,
            final String user, final List<String> selected, final List<String> displayOrder,
            final long now, final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sequence, status, failed_count, locked_until "
                + "FROM graphicalmatrix_enrollment WHERE user_id = ? FOR UPDATE")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return GraphicalMatrixVerifyResult.enrollRequired("missing_enrollment");
                }

                final String sequence = rs.getString("sequence");
                final String status = rs.getString("status");
                final int failedCount = rs.getInt("failed_count");
                final long lockedUntil = rs.getLong("locked_until");

                if (sequence == null || sequence.trim().isEmpty() || !"ACTIVE".equals(status)) {
                    return GraphicalMatrixVerifyResult.enrollRequired("inactive_or_empty_sequence");
                }
                if (!sequenceStorage.acceptedForRuntime(sequence)) {
                    return GraphicalMatrixVerifyResult.enrollRequired(
                        "sequence_storage_migration_required");
                }
                if (lockedUntil > now) {
                    return GraphicalMatrixVerifyResult.locked(
                        "locked_until=" + lockedUntil, lockedUntil);
                }

                final int expectedCount = sequenceStorage.count(sequence);
                final Set<String> unique = new HashSet<>(selected);
                final boolean selectedShapeOk =
                    selected.size() == expectedCount
                    && (duplicateSelectionsAllowed || unique.size() == selected.size())
                    && displayOrder.containsAll(selected);

                if (selectedShapeOk && sequenceStorage.matches(sequence, selected, orderedSelectionRequired,
                        duplicateSelectionsAllowed)) {
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE graphicalmatrix_enrollment "
                            + "SET failed_count = 0, locked_until = 0, updated_at = ? "
                            + "WHERE user_id = ?")) {
                        up.setLong(1, now);
                        up.setString(2, user);
                        up.executeUpdate();
                    }
                    return GraphicalMatrixVerifyResult.success("sequence_change_verified");
                }

                final int failed = incrementFailureCount(failedCount);
                final GraphicalMatrixLockoutPolicy.LockDecision lockDecision =
                    lockoutPolicy.afterFailure(failed);
                final long newLockedUntil = lockDecision.isLocked()
                    ? now + lockDecision.getLockMillis()
                    : 0L;
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET failed_count = ?, locked_until = ?, updated_at = ? "
                        + "WHERE user_id = ?")) {
                    up.setInt(1, failed);
                    up.setLong(2, newLockedUntil);
                    up.setLong(3, now);
                    up.setString(4, user);
                    up.executeUpdate();
                }

                final String detail = "failed_count=" + failed + ",selected_count=" + selected.size()
                    + ",order_mode=" + (orderedSelectionRequired ? "ordered" : "unordered")
                    + ",lock_level=" + lockDecision.getLevel().getAuditValue()
                    + ",lock_seconds=" + lockDecision.getLockSeconds()
                    + ",locked_until=" + newLockedUntil
                    + ",purpose=sequence_change";
                return lockDecision.isLocked()
                    ? GraphicalMatrixVerifyResult.locked(detail, newLockedUntil)
                    : GraphicalMatrixVerifyResult.failed(detail);
            }
        }
    }

    private static boolean matched(final List<String> expected, final List<String> selected,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed) {
        if (orderedSelectionRequired) {
            return expected.equals(selected);
        }
        if (duplicateSelectionsAllowed) {
            return multiset(expected).equals(multiset(selected));
        }
        return new HashSet<>(expected).equals(new HashSet<>(selected));
    }

    private static int incrementFailureCount(final int failedCount) {
        return failedCount < Integer.MAX_VALUE ? failedCount + 1 : Integer.MAX_VALUE;
    }

    private static java.util.Map<String, Integer> multiset(final List<String> values) {
        final java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (final String value : values) {
            out.put(value, out.getOrDefault(value, 0) + 1);
        }
        return out;
    }

    private Connection db() throws Exception {
        return GraphicalMatrixDataSource.getConnection(GraphicalMatrixRuntime.idpHome());
    }

    private void initDbIfEnabled(final Connection c) throws Exception {
        if (!dbConfig.isAutoInit()) {
            return;
        }
        if (SCHEMA_INITIALIZED.get()) {
            return;
        }
        if (SCHEMA_INITIALIZED.compareAndSet(false, true)) {
            try {
                initDb(c);
            } catch (Exception ex) {
                SCHEMA_INITIALIZED.set(false);
                throw ex;
            }
        }
    }

    private void initDb(final Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS graphicalmatrix_enrollment ("
                + "user_id VARCHAR(255) PRIMARY KEY, "
                + "sequence VARCHAR(1024) NOT NULL, "
                + "initial_sequence VARCHAR(1024) NOT NULL DEFAULT '', "
                + "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', "
                + "failed_count INT NOT NULL DEFAULT 0, "
                + "locked_until BIGINT NOT NULL DEFAULT 0, "
                + "mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix', "
                + "totp_seed VARCHAR(255), "
                + "totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED', "
                + "totp_registered_at BIGINT NOT NULL DEFAULT 0, "
                + "last_success_at BIGINT NOT NULL DEFAULT 0, "
                + "force_sequence_change INT NOT NULL DEFAULT 0, "
                + "state_version BIGINT NOT NULL DEFAULT 0, "
                + "created_at BIGINT NOT NULL, "
                + "updated_at BIGINT NOT NULL)"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS last_success_at BIGINT NOT NULL DEFAULT 0"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix'"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS totp_seed VARCHAR(255)"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED'"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS totp_registered_at BIGINT NOT NULL DEFAULT 0"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS force_sequence_change INT NOT NULL DEFAULT 0"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0"
            );
            st.executeUpdate(
                "ALTER TABLE graphicalmatrix_enrollment "
                + "ADD COLUMN IF NOT EXISTS initial_sequence VARCHAR(1024) NOT NULL DEFAULT ''"
            );
        }
    }

    private static String normalizeMethod(final String method) {
        String value = trim(method);
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        return value.toUpperCase();
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
