package io.github.yasakawa.faskw;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;

import org.opensaml.storage.StorageRecord;
import org.opensaml.storage.VersionMismatchException;

final class GraphicalMatrixLdapUserEntryRecordStore implements GraphicalMatrixLdapRecordStore {
    private static final long INITIAL_VERSION = 1L;

    private final GraphicalMatrixLdapStorageConfig config;

    GraphicalMatrixLdapUserEntryRecordStore(final GraphicalMatrixLdapStorageConfig config) {
        this.config = config;
    }

    @Override
    public boolean create(final String context, final String key, final String value,
            final Long expiration) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            final Entry entry = findUser(ldap, key);
            if (entry == null) {
                return false;
            }
            if (sameRecord(entry.record, context, key) && valid(entry.record)) {
                return false;
            }
            if (!emptyRecord(entry.record) && !sameRecord(entry.record, context, key)
                    && valid(entry.record)) {
                return false;
            }
            ldap.modifyAttributes(entry.dn, replaceAll(context, key, value, expiration, INITIAL_VERSION));
            return true;
        } catch (NamingException ex) {
            throw new IOException("Unable to create WebAuthn LDAP user-entry storage record", ex);
        }
    }

    @Override
    public StorageRecord<String> read(final String context, final String key) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final Entry entry = findUser(session.context(), key);
            if (entry == null || !sameRecord(entry.record, context, key) || !valid(entry.record)) {
                return null;
            }
            return entry.record.toStorageRecord();
        } catch (NamingException ex) {
            throw new IOException("Unable to read WebAuthn LDAP user-entry storage record", ex);
        }
    }

    @Override
    public Iterable<String> getContextKeys(final String context, final String keyPrefix)
            throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final List<String> keys = new ArrayList<>();
            final String prefix = keyPrefix != null ? keyPrefix : "";
            for (Entry entry : findContextEntries(session.context(), context)) {
                if (valid(entry.record) && entry.record.id().startsWith(prefix)) {
                    keys.add(entry.record.id());
                }
            }
            return keys;
        } catch (NamingException ex) {
            throw new IOException("Unable to enumerate WebAuthn LDAP user-entry storage keys", ex);
        }
    }

    @Override
    public Long update(final long version, final String context, final String key, final String value,
            final Long expiration) throws IOException, VersionMismatchException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            final Entry entry = findUser(ldap, key);
            if (entry == null || !sameRecord(entry.record, context, key) || !valid(entry.record)) {
                return null;
            }
            if (entry.record.version() != version) {
                throw new VersionMismatchException("LDAP storage record version mismatch");
            }
            final long newVersion = value != null ? version + 1L : version;
            ldap.modifyAttributes(entry.dn, replacePartial(value, expiration, newVersion));
            return newVersion;
        } catch (NamingException ex) {
            throw new IOException("Unable to update WebAuthn LDAP user-entry storage record", ex);
        }
    }

    @Override
    public boolean delete(final long version, final String context, final String key)
            throws IOException, VersionMismatchException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            final Entry entry = findUser(ldap, key);
            if (entry == null || !sameRecord(entry.record, context, key) || !valid(entry.record)) {
                return false;
            }
            if (entry.record.version() != version) {
                throw new VersionMismatchException("LDAP storage record version mismatch");
            }
            ldap.modifyAttributes(entry.dn, clearAll());
            return true;
        } catch (NamingException ex) {
            throw new IOException("Unable to delete WebAuthn LDAP user-entry storage record", ex);
        }
    }

    @Override
    public void reap(final String context) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            for (Entry entry : findContextEntries(ldap, context)) {
                if (entry.record.expired(System.currentTimeMillis())) {
                    ldap.modifyAttributes(entry.dn, clearAll());
                }
            }
        } catch (NamingException ex) {
            throw new IOException("Unable to reap WebAuthn LDAP user-entry storage records", ex);
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
            throw new IOException("Unable to update WebAuthn LDAP user-entry context expiration", ex);
        }
    }

    @Override
    public void deleteContext(final String context) throws IOException {
        try (GraphicalMatrixLdapRecordStore.LdapSession session =
                GraphicalMatrixLdapRecordStore.context(config)) {
            final LdapContext ldap = session.context();
            for (Entry entry : findContextEntries(ldap, context)) {
                ldap.modifyAttributes(entry.dn, clearAll());
            }
        } catch (NamingException ex) {
            throw new IOException("Unable to delete WebAuthn LDAP user-entry context", ex);
        }
    }

    private Entry findUser(final LdapContext ldap, final String key) throws NamingException {
        final SearchControls controls = new SearchControls();
        controls.setSearchScope(config.userSubtreeSearch()
            ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(config.returningAttributes());
        controls.setCountLimit(2L);
        final String escaped = GraphicalMatrixLdapRecordStore.escapeFilter(key);
        final String filter = config.userFilter()
            .replace("{id}", escaped)
            .replace("{key}", escaped)
            .replace("{user}", escaped);
        final NamingEnumeration<SearchResult> results = ldap.search(config.userBaseDn(), filter, controls);
        if (!results.hasMore()) {
            return null;
        }
        final SearchResult first = results.next();
        if (results.hasMore()) {
            throw new NamingException("LDAP WebAuthn user-entry search returned multiple entries for key=" + key);
        }
        return new Entry(first.getNameInNamespace(),
            GraphicalMatrixLdapSubtreeRecordStore.Record.from(first.getAttributes(), config));
    }

    private List<Entry> findContextEntries(final LdapContext ldap, final String context)
            throws NamingException {
        final SearchControls controls = new SearchControls();
        controls.setSearchScope(config.userSubtreeSearch()
            ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(config.returningAttributes());
        final NamingEnumeration<SearchResult> results = ldap.search(config.userBaseDn(),
            "(" + config.contextAttr() + "=" + GraphicalMatrixLdapRecordStore.escapeFilter(context) + ")",
            controls);
        final List<Entry> entries = new ArrayList<>();
        while (results.hasMore()) {
            final SearchResult result = results.next();
            entries.add(new Entry(result.getNameInNamespace(),
                GraphicalMatrixLdapSubtreeRecordStore.Record.from(result.getAttributes(), config)));
        }
        return entries;
    }

    private ModificationItem[] replaceAll(final String context, final String key, final String value,
            final Long expiration, final long version) {
        return new ModificationItem[] {
            replace(config.contextAttr(), context),
            replace(config.idAttr(), key),
            replace(config.valueAttr(), value),
            replace(config.expiresAttr(), expiration),
            replace(config.versionAttr(), String.valueOf(version))
        };
    }

    private ModificationItem[] replacePartial(final String value, final Long expiration, final long version) {
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

    private ModificationItem[] clearAll() {
        return new ModificationItem[] {
            replace(config.contextAttr(), null),
            replace(config.idAttr(), null),
            replace(config.valueAttr(), null),
            replace(config.expiresAttr(), null),
            replace(config.versionAttr(), null)
        };
    }

    private static ModificationItem replace(final String attr, final Object value) {
        final BasicAttribute attribute = value == null
            ? new BasicAttribute(attr)
            : new BasicAttribute(attr, String.valueOf(value));
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, attribute);
    }

    private static boolean sameRecord(final GraphicalMatrixLdapSubtreeRecordStore.Record record,
            final String context, final String key) {
        return record != null && context.equals(record.context()) && key.equals(record.id());
    }

    private static boolean emptyRecord(final GraphicalMatrixLdapSubtreeRecordStore.Record record) {
        return record == null || record.context().isEmpty() || record.id().isEmpty();
    }

    private static boolean valid(final GraphicalMatrixLdapSubtreeRecordStore.Record record) {
        return record != null && !record.expired(System.currentTimeMillis());
    }

    private static final class Entry {
        private final String dn;
        private final GraphicalMatrixLdapSubtreeRecordStore.Record record;

        private Entry(final String dn, final GraphicalMatrixLdapSubtreeRecordStore.Record record) {
            this.dn = dn;
            this.record = record;
        }
    }
}
