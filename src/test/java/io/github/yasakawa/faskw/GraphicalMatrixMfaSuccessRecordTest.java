/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixMfaSuccessRecordTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);
    private static final GraphicalMatrixMfaSuccessRecordOptions ON = new GraphicalMatrixMfaSuccessRecordOptions(true, true, 1);
    private static final GraphicalMatrixMfaSuccessRecord.Expectation EXPECTED =
        new GraphicalMatrixMfaSuccessRecord.Expectation("alice", "TOTP", "authn/TOTP", 7L);

    private static GraphicalMatrixMfaSuccessRecord.Snapshot row(final long version, final String time) {
        return new GraphicalMatrixMfaSuccessRecord.Snapshot(
            new GraphicalMatrixMfaSettings("ACTIVE", "TOTP", "ACTIVE", true, true, version),
            "alice", "ACTIVE", String.valueOf(version), time, "1000", true);
    }

    private static final class Store implements GraphicalMatrixMfaSuccessRecord.Store {
        GraphicalMatrixMfaSuccessRecord.Snapshot first = row(7, "1000");
        GraphicalMatrixMfaSuccessRecord.Snapshot after = row(7, "3000");
        boolean update = true;
        int reads;
        int writes;
        int failRead;
        boolean failWrite;
        @Override public GraphicalMatrixMfaSuccessRecord.Snapshot read(final boolean timestamps) throws Exception {
            reads++;
            if (reads == failRead) { throw new Exception("test-only lookup failure"); }
            return reads == 1 ? first : after;
        }
        @Override public boolean update(final GraphicalMatrixMfaSuccessRecord.Snapshot snapshot,
                final long completedAt, final int timeout) throws Exception {
            writes++;
            assertEquals(2000, completedAt);
            assertEquals(1, timeout);
            if (failWrite) { throw new Exception("test-only write failure"); }
            return update;
        }
    }

    @Test void writesOnceAfterMandatoryValidation() throws Exception {
        final Store store = new Store();
        final var result = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, store);
        assertTrue(result.valid());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.UPDATED, result.recordStatus());
        assertEquals(2000, result.completedAtMillis());
        assertEquals(1, store.reads);
        assertEquals(1, store.writes);
    }

    @Test void offKeepsMandatoryValidationAndDoesNotParseTimestamps() throws Exception {
        final Store store = new Store();
        store.first = row(7, "not-a-timestamp");
        final var result = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED,
            new GraphicalMatrixMfaSuccessRecordOptions(false, true, 1), CLOCK, store);
        assertTrue(result.valid());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.DISABLED, result.recordStatus());
        assertEquals(0, store.writes);
    }

    @Test void rejectsMissingOrChangedEnrollmentWithoutWriting() throws Exception {
        for (final var first : java.util.Arrays.asList(null, row(8, "0"))) {
            final Store store = new Store();
            store.first = first;
            final var result = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, store);
            assertFalse(result.valid());
            assertEquals(0, result.completedAtMillis());
            assertEquals(0, store.writes);
        }
    }

    @Test void initialLookupFailureRemainsAnAuthenticationFailure() {
        final Store store = new Store();
        store.failRead = 1;
        assertThrows(Exception.class, () -> GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, store));
        assertEquals(0, store.writes);
    }

    @Test void writeAndFollowupLookupFailuresAreMetadataFailuresOnly() throws Exception {
        final Store write = new Store();
        write.failWrite = true;
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED,
            GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, write).recordStatus());
        final Store read = new Store();
        read.update = false;
        read.failRead = 2;
        final var result = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, read);
        assertTrue(result.valid());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED, result.recordStatus());
        assertEquals(1, read.writes);
        assertEquals(2, read.reads);
    }

    @Test void rechecksAConflictingWriteAndRejectsAnObservedReset() throws Exception {
        final Store store = new Store();
        store.update = false;
        store.after = row(8, "3000");
        final var result = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, store);
        assertFalse(result.valid());
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.NOT_ATTEMPTED, result.recordStatus());
        assertEquals(2, store.reads);
        assertEquals(1, store.writes);
    }

    @Test void newerConcurrentTimestampIsRetainedWithoutUnboundedRetries() throws Exception {
        final Store store = new Store();
        store.update = false;
        final var newer = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, store);
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.UNCHANGED, newer.recordStatus());
        assertTrue(newer.futureTimestamp());
        final Store contended = new Store();
        contended.update = false;
        contended.after = row(7, "1500");
        assertEquals(GraphicalMatrixMfaSuccessRecord.StoreStatus.SKIPPED_CONTENDED,
            GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, contended).recordStatus());
        assertEquals(1, contended.writes);
    }

    @Test void futureAndMalformedTimestampsNeverGetSilentlyOverwritten() throws Exception {
        for (final String value : new String[] { "3000", "broken", "-1", "9999999999999999999" }) {
            final Store store = new Store();
            store.first = row(7, value);
            final var result = GraphicalMatrixMfaSuccessRecord.checkAndRecord(EXPECTED, ON, CLOCK, store);
            assertTrue(result.valid());
            assertEquals(0, store.writes);
            assertEquals("3000".equals(value) ? GraphicalMatrixMfaSuccessRecord.StoreStatus.UNCHANGED
                : GraphicalMatrixMfaSuccessRecord.StoreStatus.FAILED, result.recordStatus());
        }
    }

    @Test void validatesNewOptionsStrictly() {
        assertEquals(ON, GraphicalMatrixMfaSuccessRecordOptions.from(new Properties()));
        for (final String key : new String[] { "graphicalmatrix.mfa.successRecord.enabled",
                "graphicalmatrix.mfa.completionAudit.enabled", "graphicalmatrix.mfa.successRecord.queryTimeoutSeconds" }) {
            final Properties properties = new Properties();
            properties.setProperty(key, "typo");
            assertThrows(IllegalArgumentException.class, () -> GraphicalMatrixMfaSuccessRecordOptions.from(properties));
        }
        assertThrows(IllegalArgumentException.class, () -> new GraphicalMatrixMfaSuccessRecordOptions(true, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new GraphicalMatrixMfaSuccessRecordOptions(true, true, 6));
    }
}
