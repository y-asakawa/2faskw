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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.Rdn;

import org.opensaml.storage.StorageRecord;
import org.opensaml.storage.VersionMismatchException;

final class GraphicalMatrixLdapSubtreeRecordStore implements GraphicalMatrixLdapRecordStore {
    private static final long INITIAL_VERSION = 1L;

    private final GraphicalMatrixLdapStorageConfig config;

    GraphicalMatrixLdapSubtreeRecordStore(final GraphicalMatrixLdapStorageConfig config) {
        this.config = config;
    }

    @Override
    public boolean create(final String context, final String key, final String value,
            final Long expiration) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            final Entry existing = findEntry(ldap, context, key);
            if (existing != null && valid(existing.record)) {
                return false;
            }
            if (existing == null) {
                ldap.createSubcontext(recordDn(context, key),
                    addAttributes(context, key, value, expiration, INITIAL_VERSION));
            } else {
                ldap.modifyAttributes(existing.dn, replaceRecord(value, expiration, INITIAL_VERSION));
            }
            return true;
        } catch (NamingException ex) {
            throw new IOException("Unable to create WebAuthn LDAP storage record", ex);
        }
    }

    @Override
    public StorageRecord<String> read(final String context, final String key) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final Entry entry = findEntry(session.context(), context, key);
            return entry != null && valid(entry.record) ? entry.record.toStorageRecord() : null;
        } catch (NamingException ex) {
            throw new IOException("Unable to read WebAuthn LDAP storage record", ex);
        }
    }

    @Override
    public Iterable<String> getContextKeys(final String context, final String keyPrefix)
            throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final List<String> keys = new ArrayList<>();
            for (Entry entry : findContextEntries(session.context(), context)) {
                if (valid(entry.record) && entry.record.id().startsWith(keyPrefix != null ? keyPrefix : "")) {
                    keys.add(entry.record.id());
                }
            }
            return keys;
        } catch (NamingException ex) {
            throw new IOException("Unable to enumerate WebAuthn LDAP storage keys", ex);
        }
    }

    @Override
    public Long update(final long version, final String context, final String key, final String value,
            final Long expiration) throws IOException, VersionMismatchException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            final Entry entry = findEntry(ldap, context, key);
            if (entry == null || !valid(entry.record)) {
                return null;
            }
            if (entry.record.version != version) {
                throw new VersionMismatchException("LDAP storage record version mismatch");
            }
            final long newVersion = value != null ? version + 1L : version;
            ldap.modifyAttributes(entry.dn, replaceRecord(value, expiration, newVersion));
            return newVersion;
        } catch (NamingException ex) {
            throw new IOException("Unable to update WebAuthn LDAP storage record", ex);
        }
    }

    @Override
    public boolean delete(final long version, final String context, final String key)
            throws IOException, VersionMismatchException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            final Entry entry = findEntry(ldap, context, key);
            if (entry == null || !valid(entry.record)) {
                return false;
            }
            if (entry.record.version != version) {
                throw new VersionMismatchException("LDAP storage record version mismatch");
            }
            ldap.destroySubcontext(entry.dn);
            return true;
        } catch (NamingException ex) {
            throw new IOException("Unable to delete WebAuthn LDAP storage record", ex);
        }
    }

    @Override
    public void reap(final String context) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            for (Entry entry : findContextEntries(ldap, context)) {
                if (entry.record.expired(System.currentTimeMillis())) {
                    ldap.destroySubcontext(entry.dn);
                }
            }
        } catch (NamingException ex) {
            throw new IOException("Unable to reap WebAuthn LDAP storage records", ex);
        }
    }

    @Override
    public void updateContextExpiration(final String context, final Long expiration) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            for (Entry entry : findContextEntries(ldap, context)) {
                if (valid(entry.record)) {
                    ldap.modifyAttributes(entry.dn,
                        new ModificationItem[] {replace(config.expiresAttr(), expiration)});
                }
            }
        } catch (NamingException ex) {
            throw new IOException("Unable to update WebAuthn LDAP context expiration", ex);
        }
    }

    @Override
    public void deleteContext(final String context) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            for (Entry entry : findContextEntries(ldap, context)) {
                ldap.destroySubcontext(entry.dn);
            }
        } catch (NamingException ex) {
            throw new IOException("Unable to delete WebAuthn LDAP context", ex);
        }
    }

    static String recordRdnValue(final String context, final String key) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest((context + '\u0000' + key).getBytes(StandardCharsets.UTF_8));
            return "sr-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash LDAP storage record key", ex);
        }
    }

    private Entry findEntry(final LdapContext ldap, final String context, final String key)
            throws NamingException {
        final SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(config.returningAttributes());
        controls.setCountLimit(2L);
        final NamingEnumeration<SearchResult> results = ldap.search(config.baseDn(),
            "(&(" + config.contextAttr() + "=" + GraphicalMatrixLdapRecordStore.escapeFilter(context)
            + ")(" + config.idAttr() + "=" + GraphicalMatrixLdapRecordStore.escapeFilter(key) + "))",
            controls);
        if (!results.hasMore()) {
            return null;
        }
        final SearchResult first = results.next();
        if (results.hasMore()) {
            throw new NamingException("LDAP storage search returned multiple entries for context/key");
        }
        return new Entry(first.getNameInNamespace(), Record.from(first.getAttributes(), config));
    }

    private List<Entry> findContextEntries(final LdapContext ldap, final String context)
            throws NamingException {
        final SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(config.returningAttributes());
        final NamingEnumeration<SearchResult> results = ldap.search(config.baseDn(),
            "(" + config.contextAttr() + "=" + GraphicalMatrixLdapRecordStore.escapeFilter(context) + ")",
            controls);
        final List<Entry> entries = new ArrayList<>();
        while (results.hasMore()) {
            final SearchResult result = results.next();
            entries.add(new Entry(result.getNameInNamespace(), Record.from(result.getAttributes(), config)));
        }
        return entries;
    }

    private String recordDn(final String context, final String key) {
        return config.recordRdnAttr() + "=" + Rdn.escapeValue(recordRdnValue(context, key))
            + "," + config.baseDn();
    }

    private Attributes addAttributes(final String context, final String key, final String value,
            final Long expiration, final long version) {
        final BasicAttributes attributes = new BasicAttributes(true);
        final BasicAttribute objectClass = new BasicAttribute("objectClass");
        for (String item : config.objectClasses()) {
            objectClass.add(item);
        }
        attributes.put(objectClass);
        attributes.put(config.recordRdnAttr(), recordRdnValue(context, key));
        attributes.put(config.contextAttr(), context);
        attributes.put(config.idAttr(), key);
        attributes.put(config.valueAttr(), value);
        attributes.put(config.versionAttr(), String.valueOf(version));
        if (expiration != null) {
            attributes.put(config.expiresAttr(), String.valueOf(expiration));
        }
        return attributes;
    }

    private ModificationItem[] replaceRecord(final String value, final Long expiration, final long version) {
        if (value == null) {
            return new ModificationItem[] {
                replace(config.expiresAttr(), expiration),
                replace(config.versionAttr(), String.valueOf(version))
            };
        }
        return new ModificationItem[] {
            replace(config.valueAttr(), value),
            replace(config.expiresAttr(), expiration),
            replace(config.versionAttr(), String.valueOf(version))
        };
    }

    private static ModificationItem replace(final String attr, final Object value) {
        final BasicAttribute attribute = value == null
            ? new BasicAttribute(attr)
            : new BasicAttribute(attr, String.valueOf(value));
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, attribute);
    }

    private static boolean valid(final Record record) {
        return record != null && !record.expired(System.currentTimeMillis());
    }

    private static final class Entry {
        private final String dn;
        private final Record record;

        private Entry(final String dn, final Record record) {
            this.dn = dn;
            this.record = record;
        }
    }

    static final class Record {
        private final String context;
        private final String id;
        private final String value;
        private final Long expiration;
        private final long version;

        private Record(final String context, final String id, final String value,
                final Long expiration, final long version) {
            this.context = context;
            this.id = id;
            this.value = value;
            this.expiration = expiration;
            this.version = version;
        }

        static Record from(final Attributes attributes, final GraphicalMatrixLdapStorageConfig config) {
            return new Record(
                attr(attributes, config.contextAttr()),
                attr(attributes, config.idAttr()),
                attr(attributes, config.valueAttr()),
                nullableLong(attr(attributes, config.expiresAttr())),
                longValue(attr(attributes, config.versionAttr()), INITIAL_VERSION)
            );
        }

        String context() {
            return context;
        }

        String id() {
            return id;
        }

        long version() {
            return version;
        }

        boolean expired(final long now) {
            return expiration != null && expiration <= now;
        }

        StorageRecord<String> toStorageRecord() {
            return new GraphicalMatrixLdapStorageRecord<>(value, expiration, version);
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

        private static Long nullableLong(final String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.valueOf(value.trim());
            } catch (NumberFormatException ex) {
                return null;
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
    }
}
