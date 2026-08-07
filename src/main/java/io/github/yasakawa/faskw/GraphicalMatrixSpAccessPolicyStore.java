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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains the last known good immutable access policy snapshot. */
final class GraphicalMatrixSpAccessPolicyStore {
    private static final Logger LOG =
        LoggerFactory.getLogger(GraphicalMatrixSpAccessPolicyStore.class);

    private final Path policyPath;
    private final Path registryPath;
    private final Path catalogPath;
    private final Set<String> blockedAttributes;
    private final Duration reloadInterval;
    private final Object reloadLock = new Object();

    private volatile GraphicalMatrixSpAccessPolicy snapshot;
    private volatile Object fileKey;
    private volatile long modifiedMillis = Long.MIN_VALUE;
    private volatile long nextCheckNanos;

    GraphicalMatrixSpAccessPolicyStore(final GraphicalMatrixSpManagementConfig config)
            throws IOException {
        policyPath = config.accessPolicyPath();
        registryPath = config.registryPath();
        catalogPath = config.attributeCatalogPath();
        blockedAttributes = config.blockedAttributes();
        reloadInterval = Duration.ofSeconds(config.accessReloadIntervalSeconds());
        snapshot = loadValidated();
        final BasicFileAttributes attributes = attributes(policyPath);
        fileKey = attributes.fileKey();
        modifiedMillis = attributes.lastModifiedTime().toMillis();
        nextCheckNanos = System.nanoTime() + reloadInterval.toNanos();
    }

    GraphicalMatrixSpAccessPolicy.Policy policyFor(final String entityId) {
        reloadIfDue();
        return snapshot.findByEntityId(entityId);
    }

    private void reloadIfDue() {
        final long now = System.nanoTime();
        if (now < nextCheckNanos) {
            return;
        }
        synchronized (reloadLock) {
            if (now < nextCheckNanos) {
                return;
            }
            nextCheckNanos = now + reloadInterval.toNanos();
            try {
                final BasicFileAttributes attributes = attributes(policyPath);
                if (modifiedMillis == attributes.lastModifiedTime().toMillis()
                        && java.util.Objects.equals(fileKey, attributes.fileKey())) {
                    return;
                }
                final GraphicalMatrixSpAccessPolicy candidate = loadValidated();
                snapshot = candidate;
                modifiedMillis = attributes.lastModifiedTime().toMillis();
                fileKey = attributes.fileKey();
                LOG.info("Reloaded 2FAS-KW SP access policy at {}", Instant.now());
            } catch (Exception ex) {
                LOG.error("Unable to reload 2FAS-KW SP access policy; retaining last known good "
                    + "snapshot", ex);
            }
        }
    }

    private GraphicalMatrixSpAccessPolicy loadValidated() throws IOException {
        secureRegularFile(policyPath);
        secureRegularFile(registryPath);
        secureRegularFile(catalogPath);
        final GraphicalMatrixSpAccessPolicy policy = GraphicalMatrixSpAccessPolicy.load(policyPath);
        final GraphicalMatrixSpRegistry registry = GraphicalMatrixSpRegistry.load(registryPath);
        final GraphicalMatrixAttributeCatalog catalog =
            GraphicalMatrixAttributeCatalog.load(catalogPath);
        for (final GraphicalMatrixSpAccessPolicy.Policy item : policy.policies()) {
            final GraphicalMatrixSpRegistry.Entry entry = registry.entries().stream()
                .filter(candidate -> candidate.name().equals(item.name())).findFirst().orElse(null);
            if (entry == null || !entry.entityId().equals(item.entityId())) {
                throw new IOException("access policy identity does not match registry: "
                    + item.name());
            }
            if (entry.accessPolicyEnabled() != item.enabled()
                    || entry.accessPolicyRevision() != item.revision()) {
                throw new IOException("access policy revision does not match registry: "
                    + item.name());
            }
            for (final GraphicalMatrixSpAccessPolicy.Rule rule :
                    java.util.stream.Stream.concat(item.allow().stream(), item.deny().stream())
                        .toList()) {
                final GraphicalMatrixAttributeCatalog.Attribute attribute;
                try {
                    attribute = catalog.requireAttribute(rule.attributeId());
                } catch (IllegalArgumentException ex) {
                    throw new IOException("access policy attribute is absent from catalog: "
                        + rule.attributeId(), ex);
                }
                if (!attribute.accessApproved()
                        || catalog.isBlocked(attribute.id(), blockedAttributes)) {
                    throw new IOException("access policy attribute is not approved: "
                        + attribute.id());
                }
            }
        }
        for (final GraphicalMatrixSpRegistry.Entry entry : registry.entries()) {
            final GraphicalMatrixSpAccessPolicy.Policy item = policy.find(entry.name());
            if ((entry.accessPolicyEnabled() || entry.accessPolicyRevision() > 0)
                    && item == null) {
                throw new IOException("registry references a missing access policy: "
                    + entry.name());
            }
        }
        return policy;
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void secureRegularFile(final Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("governance file must be a non-symlink regular file: " + path);
        }
        try {
            final PosixFileAttributes attributes = Files.readAttributes(path,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            final Set<PosixFilePermission> permissions = attributes.permissions();
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IOException("governance file permissions are unsafe: " + path);
            }
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are validated on supported production filesystems.
        }
        try {
            final Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (links instanceof Number count && count.longValue() != 1L) {
                throw new IOException("governance file must not have hard links: " + path);
            }
        } catch (UnsupportedOperationException ignored) {
            // The unix view is not available on every test filesystem.
        }
    }
}
