/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixLdapEnrollmentStoreTest {
    @Test
    void existingStateVersionUsesAtomicCompareAndAdvanceOperations() throws Exception {
        final ModificationItem[] updates = GraphicalMatrixLdapEnrollmentStore.stateVersionUpdate(
            "gmStateVersion", 7L, true, true);

        assertEquals(2, updates.length);
        assertEquals(DirContext.REMOVE_ATTRIBUTE, updates[0].getModificationOp());
        assertEquals("7", updates[0].getAttribute().get());
        assertEquals(DirContext.ADD_ATTRIBUTE, updates[1].getModificationOp());
        assertEquals("8", updates[1].getAttribute().get());
    }

    @Test
    void missingStateVersionUsesAddAsTheCompareAndSetGuard() throws Exception {
        final ModificationItem[] updates = GraphicalMatrixLdapEnrollmentStore.stateVersionUpdate(
            "gmStateVersion", 0L, false, true);

        assertEquals(1, updates.length);
        assertEquals(DirContext.ADD_ATTRIBUTE, updates[0].getModificationOp());
        assertEquals("1", updates[0].getAttribute().get());
    }

    @Test
    void webAuthnRecordVersionUsesAtomicCompareAndAdvanceOperations() throws Exception {
        final ModificationItem[] updates = GraphicalMatrixLdapSubtreeRecordStore.stateVersionUpdate(
            "gmStorageVersion", 3L, 4L);

        assertEquals(2, updates.length);
        assertEquals(DirContext.REMOVE_ATTRIBUTE, updates[0].getModificationOp());
        assertEquals("3", updates[0].getAttribute().get());
        assertEquals(DirContext.ADD_ATTRIBUTE, updates[1].getModificationOp());
        assertEquals("4", updates[1].getAttribute().get());
    }
}
