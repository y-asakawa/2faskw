package io.github.yasakawa.faskw;

import org.opensaml.storage.StorageRecord;

final class GraphicalMatrixLdapStorageRecord<T> extends StorageRecord<T> {
    GraphicalMatrixLdapStorageRecord(final String value, final Long expiration, final long version) {
        super(value, expiration);
        setVersion(version);
    }
}
