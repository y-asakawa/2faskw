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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class GraphicalMatrixSpFiles {
    private GraphicalMatrixSpFiles() {
    }

    static void atomicWrite(final Path path, final String content) throws IOException {
        atomicWrite(path, content.getBytes(StandardCharsets.UTF_8));
    }

    static void atomicWrite(final Path path, final byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path)) {
            throw new IOException("refusing to replace a symbolic link: " + path);
        }
        final PosixFileAttributes existing = readPosixAttributes(path);
        final Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            preservePosixAttributes(temporary, existing);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void appendAudit(final Path path, final String content) throws IOException {
        Files.createDirectories(path.getParent());
        final Set<OpenOption> options = Set.of(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(path, options,
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-r-----")))) {
            final ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        }
    }

    private static PosixFileAttributes readPosixAttributes(final Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX attributes are not available on every test filesystem.
        }
        return null;
    }

    private static void preservePosixAttributes(final Path path, final PosixFileAttributes attributes)
            throws IOException {
        if (attributes == null) {
            return;
        }
        final PosixFileAttributeView view = Files.getFileAttributeView(path,
            PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        view.setOwner(attributes.owner());
        view.setGroup(attributes.group());
        view.setPermissions(attributes.permissions());
    }
}
