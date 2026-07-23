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

import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

final class GraphicalMatrixLdapEnrollmentStore {
    private final GraphicalMatrixLdapConfig config;
    private final GraphicalMatrixSequenceStorage sequenceStorage;
    private final GraphicalMatrixTotpSeedStorage totpSeedStorage;

    GraphicalMatrixLdapEnrollmentStore(final String idpHome,
            final GraphicalMatrixSequenceStorage sequenceStorage,
            final GraphicalMatrixTotpSeedStorage totpSeedStorage) {
        this.config = GraphicalMatrixLdapConfig.load(idpHome);
        this.sequenceStorage = sequenceStorage;
        this.totpSeedStorage = totpSeedStorage;
    }

    GraphicalMatrixEnrollment findEnrollment(final String user) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            return entry != null ? entry.record.toEnrollment() : null;
        }
    }

    GraphicalMatrixMfaSettings findMfaSettings(final String user) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            if (entry == null) {
                return null;
            }
            return new GraphicalMatrixMfaSettings(
                entry.record.mfaMethod,
                entry.record.totpStatus,
                !entry.record.totpSeed.isEmpty()
            );
        }
    }

    String prepareTotpRegistration(final String user, final long now) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            if (entry == null || !"ACTIVE".equals(entry.record.status)
                    || entry.record.lockedUntil > now) {
                return null;
            }
            if (!"TOTP".equals(normalizeMethod(entry.record.mfaMethod))) {
                return null;
            }
            if ("ACTIVE".equalsIgnoreCase(entry.record.totpStatus) && !entry.record.totpSeed.isEmpty()) {
                return null;
            }
            if ("PENDING".equalsIgnoreCase(entry.record.totpStatus) && !entry.record.totpSeed.isEmpty()) {
                return totpSeedStorage.decode(entry.record.totpSeed);
            }

            final String seed = GraphicalMatrixTotpSupport.newBase32Seed();
            modify(context, entry.dn,
                replace(config.totpSeedAttr(), totpSeedStorage.encode(seed)),
                replace(config.totpStatusAttr(), "PENDING"),
                replace(config.updatedAtAttr(), String.valueOf(now))
            );
            return seed;
        }
    }

    GraphicalMatrixVerifyResult verifyAndActivateTotp(final String user, final String code,
            final long now) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            if (entry == null) {
                return GraphicalMatrixVerifyResult.enrollRequired("missing_enrollment");
            }
            if (!"ACTIVE".equals(entry.record.status)) {
                return GraphicalMatrixVerifyResult.enrollRequired("inactive_enrollment");
            }
            if (entry.record.lockedUntil > now) {
                return GraphicalMatrixVerifyResult.locked(
                    "locked_until=" + entry.record.lockedUntil, entry.record.lockedUntil);
            }
            if (!"TOTP".equals(normalizeMethod(entry.record.mfaMethod))) {
                return GraphicalMatrixVerifyResult.enrollRequired("not_totp_method");
            }
            if (entry.record.totpSeed.isEmpty() || !"PENDING".equalsIgnoreCase(entry.record.totpStatus)) {
                return GraphicalMatrixVerifyResult.enrollRequired("totp_not_pending");
            }

            final String seed = totpSeedStorage.decode(entry.record.totpSeed);
            if (!GraphicalMatrixTotpSupport.verify(seed, code, now, 1)) {
                return GraphicalMatrixVerifyResult.failed("totp_registration_code_mismatch");
            }

            modify(context, entry.dn,
                replace(config.totpStatusAttr(), "ACTIVE"),
                replace(config.totpRegisteredAtAttr(), String.valueOf(now)),
                replace(config.lastSuccessAtAttr(), String.valueOf(now)),
                replace(config.updatedAtAttr(), String.valueOf(now))
            );
            return GraphicalMatrixVerifyResult.success("totp_registered");
        }
    }

    GraphicalMatrixVerifyResult verify(final String user, final List<String> selected,
            final List<String> displayOrder, final long now,
            final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            final GraphicalMatrixVerifyResult precheck = precheckGraphical(entry, now);
            if (precheck != null) {
                return precheck;
            }
            return verifySelection(context, entry, selected, displayOrder, now, lockoutPolicy,
                orderedSelectionRequired, duplicateSelectionsAllowed, false);
        }
    }

    GraphicalMatrixVerifyResult verifyForSequenceChange(final String user, final List<String> selected,
            final List<String> displayOrder, final long now,
            final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            final GraphicalMatrixVerifyResult precheck = precheckGraphical(entry, now);
            if (precheck != null) {
                return precheck;
            }
            return verifySelection(context, entry, selected, displayOrder, now, lockoutPolicy,
                orderedSelectionRequired, duplicateSelectionsAllowed, true);
        }
    }

    boolean updateSequence(final String user, final String storedSequence, final long now,
            final long expectedStateVersion) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            if (entry == null || !"ACTIVE".equals(entry.record.status)
                    || entry.record.lockedUntil > now
                    || entry.record.stateVersion != expectedStateVersion) {
                return false;
            }
            modify(context, entry.dn, withStateVersionUpdate(entry, true,
                replace(config.sequenceAttr(), storedSequence),
                replace(config.forceSequenceChangeAttr(), "0"),
                replace(config.updatedAtAttr(), String.valueOf(now))
            ));
            return true;
        }
    }

    boolean updateMfaMethod(final String user, final String method, final long now) throws Exception {
        return updateMfaMethod(user, method, now, null);
    }

    boolean updateMfaMethodIfCurrent(final String user, final String method, final long now,
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

        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            if (entry == null || !"ACTIVE".equals(entry.record.status)
                    || entry.record.lockedUntil > now
                    || (expectedStateVersion != null
                        && entry.record.stateVersion != expectedStateVersion.longValue())) {
                return false;
            }
            if ("TOTP".equals(normalized)) {
                modify(context, entry.dn, withStateVersionUpdate(entry, expectedStateVersion != null,
                    replace(config.mfaMethodAttr(), "TOTP"),
                    replace(config.totpSeedAttr(), ""),
                    replace(config.totpStatusAttr(), "UNREGISTERED"),
                    replace(config.totpRegisteredAtAttr(), "0"),
                    replace(config.failedCountAttr(), "0"),
                    replace(config.lockedUntilAttr(), "0"),
                    replace(config.updatedAtAttr(), String.valueOf(now))
                ));
                return true;
            }
            if ("WEBAUTHN".equals(normalized)) {
                modify(context, entry.dn, withStateVersionUpdate(entry, expectedStateVersion != null,
                    replace(config.mfaMethodAttr(), "WebAuthn"),
                    replace(config.failedCountAttr(), "0"),
                    replace(config.lockedUntilAttr(), "0"),
                    replace(config.updatedAtAttr(), String.valueOf(now))
                ));
                return true;
            }
            modify(context, entry.dn, withStateVersionUpdate(entry, expectedStateVersion != null,
                replace(config.mfaMethodAttr(), "GraphicalMatrix"),
                replace(config.failedCountAttr(), "0"),
                replace(config.lockedUntilAttr(), "0"),
                replace(config.updatedAtAttr(), String.valueOf(now))
            ));
            return true;
        }
    }

    boolean isForceSequenceChangeRequired(final String user) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            return entry != null && entry.record.forceSequenceChange;
        }
    }

    String findActiveTotpSeed(final String user) throws Exception {
        try (LdapSession session = context()) {
            final LdapContext context = session.context;
            final Entry entry = findEntry(context, user);
            if (entry == null || !"ACTIVE".equals(entry.record.status)
                    || !"TOTP".equals(normalizeMethod(entry.record.mfaMethod))
                    || !"ACTIVE".equalsIgnoreCase(entry.record.totpStatus)
                    || entry.record.totpSeed.isEmpty()) {
                return "";
            }
            return totpSeedStorage.decode(entry.record.totpSeed);
        }
    }

    private GraphicalMatrixVerifyResult precheckGraphical(final Entry entry, final long now) {
        if (entry == null) {
            return GraphicalMatrixVerifyResult.enrollRequired("missing_enrollment");
        }
        if (entry.record.sequence.isEmpty() || !"ACTIVE".equals(entry.record.status)) {
            return GraphicalMatrixVerifyResult.enrollRequired("inactive_or_empty_sequence");
        }
        if (!sequenceStorage.acceptedForRuntime(entry.record.sequence)) {
            return GraphicalMatrixVerifyResult.enrollRequired("sequence_storage_migration_required");
        }
        if (entry.record.lockedUntil > now) {
            return GraphicalMatrixVerifyResult.locked(
                "locked_until=" + entry.record.lockedUntil, entry.record.lockedUntil);
        }
        return null;
    }

    private GraphicalMatrixVerifyResult verifySelection(final LdapContext context, final Entry entry,
            final List<String> selected, final List<String> displayOrder, final long now,
            final GraphicalMatrixLockoutPolicy lockoutPolicy,
            final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed, final boolean sequenceChange) throws Exception {
        final int expectedCount = sequenceStorage.count(entry.record.sequence);
        final Set<String> unique = new HashSet<>(selected);
        final boolean selectedShapeOk =
            selected.size() == expectedCount
            && (duplicateSelectionsAllowed || unique.size() == selected.size())
            && displayOrder.containsAll(selected);

        if (selectedShapeOk && sequenceStorage.matches(entry.record.sequence, selected,
                orderedSelectionRequired, duplicateSelectionsAllowed)) {
            if (sequenceChange) {
                modify(context, entry.dn,
                    replace(config.failedCountAttr(), "0"),
                    replace(config.lockedUntilAttr(), "0"),
                    replace(config.updatedAtAttr(), String.valueOf(now))
                );
                return GraphicalMatrixVerifyResult.success("sequence_change_verified");
            }
            modify(context, entry.dn,
                replace(config.failedCountAttr(), "0"),
                replace(config.lockedUntilAttr(), "0"),
                replace(config.lastSuccessAtAttr(), String.valueOf(now)),
                replace(config.updatedAtAttr(), String.valueOf(now))
            );
            return GraphicalMatrixVerifyResult.success();
        }

        final int failed = incrementFailureCount(entry.record.failedCount);
        final GraphicalMatrixLockoutPolicy.LockDecision lockDecision =
            lockoutPolicy.afterFailure(failed);
        final long newLockedUntil = lockDecision.isLocked()
            ? now + lockDecision.getLockMillis()
            : 0L;
        modify(context, entry.dn,
            replace(config.failedCountAttr(), String.valueOf(failed)),
            replace(config.lockedUntilAttr(), String.valueOf(newLockedUntil)),
            replace(config.updatedAtAttr(), String.valueOf(now))
        );

        final String detail = "failed_count=" + failed + ",selected_count=" + selected.size()
            + ",order_mode=" + (orderedSelectionRequired ? "ordered" : "unordered")
            + ",lock_level=" + lockDecision.getLevel().getAuditValue()
            + ",lock_seconds=" + lockDecision.getLockSeconds()
            + ",locked_until=" + newLockedUntil
            + (sequenceChange ? ",purpose=sequence_change" : "");
        return lockDecision.isLocked()
            ? GraphicalMatrixVerifyResult.locked(detail, newLockedUntil)
            : GraphicalMatrixVerifyResult.failed(detail);
    }

    private Entry findEntry(final LdapContext context, final String user) throws NamingException {
        if (user == null || user.isBlank()) {
            return null;
        }
        final SearchControls controls = new SearchControls();
        controls.setSearchScope(config.subtreeSearch()
            ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(config.returningAttributes());
        controls.setCountLimit(2L);

        final String filter = config.userFilter().replace("{user}", escapeFilter(user));
        final NamingEnumeration<SearchResult> results =
            context.search(config.baseDn(), filter, controls);
        if (!results.hasMore()) {
            return null;
        }
        final SearchResult first = results.next();
        if (results.hasMore()) {
            throw new NamingException("LDAP user search returned multiple entries for user=" + user);
        }
        return new Entry(first.getNameInNamespace(), Record.from(first.getAttributes(), config));
    }

    private static int incrementFailureCount(final int failedCount) {
        return failedCount < Integer.MAX_VALUE ? failedCount + 1 : Integer.MAX_VALUE;
    }

    private LdapSession context() throws NamingException {
        final Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.url());
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(config.connectTimeoutMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(config.responseTimeoutMillis()));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, config.bindDn());
        env.put(Context.SECURITY_CREDENTIALS, config.bindCredential());
        return new LdapSession(new InitialLdapContext(env, null));
    }

    private static void modify(final LdapContext context, final String dn,
            final ModificationItem... items) throws NamingException {
        context.modifyAttributes(dn, items);
    }

    private ModificationItem[] withStateVersionUpdate(final Entry entry, final boolean compareCurrent,
            final ModificationItem... items) {
        final int extraItems = compareCurrent && entry.record.stateVersionPresent ? 2 : 1;
        final ModificationItem[] all = new ModificationItem[items.length + extraItems];
        System.arraycopy(items, 0, all, 0, items.length);
        final String current = String.valueOf(entry.record.stateVersion);
        final String next = String.valueOf(entry.record.stateVersion + 1);
        if (compareCurrent && entry.record.stateVersionPresent) {
            all[items.length] = remove(config.stateVersionAttr(), current);
            all[items.length + 1] = add(config.stateVersionAttr(), next);
        } else {
            all[items.length] = replace(config.stateVersionAttr(), next);
        }
        return all;
    }

    private static ModificationItem replace(final String attr, final String value) {
        final BasicAttribute attribute = value == null || value.isEmpty()
            ? new BasicAttribute(attr)
            : new BasicAttribute(attr, value);
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, attribute);
    }

    private static ModificationItem add(final String attr, final String value) {
        return new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    private static ModificationItem remove(final String attr, final String value) {
        return new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    private static String attr(final Attributes attributes, final String name) {
        if (attributes == null || name == null || name.isBlank()) {
            return "";
        }
        try {
            final Attribute attribute = attributes.get(name);
            if (attribute == null || attribute.get() == null) {
                return "";
            }
            return String.valueOf(attribute.get()).trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean hasAttribute(final Attributes attributes, final String name) {
        if (attributes == null || name == null || name.isBlank()) {
            return false;
        }
        return attributes.get(name) != null;
    }

    private static int intValue(final String value, final int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long longValue(final String value, final long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String normalizeMethod(final String method) {
        String value = method != null ? method.trim() : "";
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String escapeFilter(final String value) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\5c");
            } else if (c == '*') {
                out.append("\\2a");
            } else if (c == '(') {
                out.append("\\28");
            } else if (c == ')') {
                out.append("\\29");
            } else if (c == '\u0000') {
                out.append("\\00");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Entry {
        private final String dn;
        private final Record record;

        private Entry(final String dn, final Record record) {
            this.dn = dn;
            this.record = record;
        }
    }

    private static final class LdapSession implements AutoCloseable {
        private final LdapContext context;

        private LdapSession(final LdapContext context) {
            this.context = context;
        }

        @Override
        public void close() throws NamingException {
            context.close();
        }
    }

    private static final class Record {
        private final String sequence;
        private final String status;
        private final int failedCount;
        private final long lockedUntil;
        private final String mfaMethod;
        private final String totpSeed;
        private final String totpStatus;
        private final boolean forceSequenceChange;
        private final long stateVersion;
        private final boolean stateVersionPresent;

        private Record(final String sequence, final String status, final int failedCount,
                final long lockedUntil, final String mfaMethod, final String totpSeed,
                final String totpStatus, final boolean forceSequenceChange,
                final long stateVersion, final boolean stateVersionPresent) {
            this.sequence = sequence;
            this.status = status;
            this.failedCount = failedCount;
            this.lockedUntil = lockedUntil;
            this.mfaMethod = mfaMethod;
            this.totpSeed = totpSeed;
            this.totpStatus = totpStatus;
            this.forceSequenceChange = forceSequenceChange;
            this.stateVersion = stateVersion;
            this.stateVersionPresent = stateVersionPresent;
        }

        private static Record from(final Attributes attributes, final GraphicalMatrixLdapConfig config) {
            return new Record(
                attr(attributes, config.sequenceAttr()),
                defaultValue(attr(attributes, config.statusAttr()), "ACTIVE"),
                intValue(attr(attributes, config.failedCountAttr()), 0),
                longValue(attr(attributes, config.lockedUntilAttr()), 0L),
                defaultValue(attr(attributes, config.mfaMethodAttr()), "GraphicalMatrix"),
                attr(attributes, config.totpSeedAttr()),
                defaultValue(attr(attributes, config.totpStatusAttr()), "UNREGISTERED"),
                intValue(attr(attributes, config.forceSequenceChangeAttr()), 0) != 0,
                longValue(attr(attributes, config.stateVersionAttr()), 0L),
                hasAttribute(attributes, config.stateVersionAttr())
            );
        }

        private GraphicalMatrixEnrollment toEnrollment() {
            return new GraphicalMatrixEnrollment(
                sequence, status, failedCount, lockedUntil, forceSequenceChange, stateVersion);
        }

        private static String defaultValue(final String value, final String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }
}
