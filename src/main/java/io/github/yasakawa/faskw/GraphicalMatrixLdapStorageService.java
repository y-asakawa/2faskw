package io.github.yasakawa.faskw;

import java.io.IOException;

import net.shibboleth.shared.collection.Pair;
import net.shibboleth.shared.component.ComponentInitializationException;

import org.opensaml.storage.AbstractStorageService;
import org.opensaml.storage.EnumeratableStorageService;
import org.opensaml.storage.StorageRecord;
import org.opensaml.storage.VersionMismatchException;

public final class GraphicalMatrixLdapStorageService extends AbstractStorageService
        implements EnumeratableStorageService {
    private String idpHome = System.getProperty("idp.home", "/opt/shibboleth-idp");
    private GraphicalMatrixLdapRecordStore store;

    public void setIdpHome(final String home) {
        if (home != null && !home.isBlank()) {
            idpHome = home.trim();
        }
    }

    @Override
    public boolean isServerSide() {
        return true;
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    @Override
    protected void doInitialize() throws ComponentInitializationException {
        try {
            final GraphicalMatrixLdapStorageConfig config =
                GraphicalMatrixLdapStorageConfig.load(idpHome);
            store = GraphicalMatrixLdapStorageConfig.LAYOUT_USER_ENTRY.equals(config.layout())
                ? new GraphicalMatrixLdapUserEntryRecordStore(config)
                : new GraphicalMatrixLdapSubtreeRecordStore(config);
            setContextSize(255);
            setKeySize(255);
            setValueSize(Integer.MAX_VALUE);
        } catch (RuntimeException ex) {
            throw new ComponentInitializationException("Unable to initialize GraphicalMatrix LDAP StorageService", ex);
        }
        super.doInitialize();
    }

    @Override
    public boolean create(final String context, final String key, final String value,
            final Long expiration) throws IOException {
        return store.create(context, key, value, expiration);
    }

    @Override
    public <T> StorageRecord<T> read(final String context, final String key) throws IOException {
        final StorageRecord<String> record = store.read(context, key);
        return cast(record);
    }

    @Override
    public <T> Pair<Long, StorageRecord<T>> read(final String context, final String key,
            final long version) throws IOException {
        final StorageRecord<T> record = read(context, key);
        if (record == null) {
            return new Pair<>(null, null);
        }
        if (record.getVersion() == version) {
            return new Pair<>(version, null);
        }
        return new Pair<>(record.getVersion(), record);
    }

    @Override
    public Iterable<String> getContextKeys(final String context, final String keyPrefix)
            throws IOException {
        return store.getContextKeys(context, keyPrefix);
    }

    @Override
    public boolean update(final String context, final String key, final String value,
            final Long expiration) throws IOException {
        final StorageRecord<String> record = store.read(context, key);
        if (record == null) {
            return false;
        }
        try {
            return store.update(record.getVersion(), context, key, value, expiration) != null;
        } catch (VersionMismatchException ex) {
            return false;
        }
    }

    @Override
    public Long updateWithVersion(final long version, final String context, final String key,
            final String value, final Long expiration) throws IOException, VersionMismatchException {
        return store.update(version, context, key, value, expiration);
    }

    @Override
    public boolean updateExpiration(final String context, final String key,
            final Long expiration) throws IOException {
        final StorageRecord<String> record = store.read(context, key);
        if (record == null) {
            return false;
        }
        try {
            return store.update(record.getVersion(), context, key, null, expiration) != null;
        } catch (VersionMismatchException ex) {
            return false;
        }
    }

    @Override
    public boolean delete(final String context, final String key) throws IOException {
        final StorageRecord<String> record = store.read(context, key);
        if (record == null) {
            return false;
        }
        try {
            return store.delete(record.getVersion(), context, key);
        } catch (VersionMismatchException ex) {
            return false;
        }
    }

    @Override
    public boolean deleteWithVersion(final long version, final String context, final String key)
            throws IOException, VersionMismatchException {
        return store.delete(version, context, key);
    }

    @Override
    public void reap(final String context) throws IOException {
        store.reap(context);
    }

    @Override
    public void updateContextExpiration(final String context, final Long expiration)
            throws IOException {
        store.updateContextExpiration(context, expiration);
    }

    @Override
    public void deleteContext(final String context) throws IOException {
        store.deleteContext(context);
    }

    @SuppressWarnings("unchecked")
    private static <T> StorageRecord<T> cast(final StorageRecord<String> record) {
        return (StorageRecord<T>) record;
    }
}
