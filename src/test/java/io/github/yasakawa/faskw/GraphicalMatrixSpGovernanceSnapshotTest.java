/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSpGovernanceSnapshotTest {
    @TempDir Path home;

    @Test void restoresBytesPermissionsAndRemovesOnlyItsNewFiles() throws Exception {
        final Path existing = Files.writeString(home.resolve("config.xml"), "original");
        final Path created = home.resolve("policy.json");
        final var permissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(existing, permissions);
        final Object snapshot = snapshot(existing, created);
        Files.writeString(existing, "ours");
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(created, "new policy");
        invoke(snapshot, "recordWrite", existing);
        invoke(snapshot, "recordWrite", created);
        invoke(snapshot, "restore");
        assertEquals("original", Files.readString(existing));
        assertEquals(permissions, Files.getPosixFilePermissions(existing));
        assertFalse(Files.exists(created));
    }

    @Test void refusesToOverwriteAnExternalEditDuringRecovery() throws Exception {
        final Path existing = Files.writeString(home.resolve("config.xml"), "original");
        final Object snapshot = snapshot(existing);
        Files.writeString(existing, "ours");
        invoke(snapshot, "recordWrite", existing);
        Files.writeString(existing, "external edit");
        final var failure = assertThrows(InvocationTargetException.class, () -> invoke(snapshot, "restore"));
        assertTrue(failure.getCause().getMessage().contains("RECOVERY_REQUIRED"));
        assertEquals("external edit", Files.readString(existing));
    }

    private Object snapshot(final Path... paths) throws Exception {
        final Class<?> type = Class.forName(GraphicalMatrixSpGovernanceTool.class.getName() + "$Snapshot");
        final var constructor = type.getDeclaredConstructor(Path[].class);
        constructor.setAccessible(true);
        final Object snapshot = constructor.newInstance((Object) paths);
        final var guarded = type.getDeclaredField("guarded");
        guarded.setAccessible(true); guarded.setBoolean(snapshot, true);
        return snapshot;
    }

    private void invoke(final Object target, final String name, final Path... paths) throws Exception {
        final var method = target.getClass().getDeclaredMethod(name, paths.length == 0 ? new Class<?>[0] : new Class<?>[] {Path.class});
        method.setAccessible(true);
        method.invoke(target, (Object[]) paths);
    }
}
