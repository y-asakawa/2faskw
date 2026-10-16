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

package io.github.yasakawa.faskw.spimport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.DirectoryStream;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

final class SafeFiles {
    static final int MAX_FILE = 50 * 1024 * 1024;
    static byte[] read(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > limit) throw new IOException("not a regular file or size limit exceeded: " + display(file.toString()));
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("RESOURCE_LIMIT_EXCEEDED: " + file);
            return bytes;
        }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static String display(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) result.append(String.format("\\u%04x", c));
            else result.appendCodePoint(c);
        });
        return result.toString();
    }

    static void directory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) throw new IOException("unsafe output directory: " + path);
        } else {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
    }

    static void write(Path path, byte[] bytes) throws IOException {
        output(path, bytes, false);
    }

    static void append(Path path, String text) throws IOException { output(path, text.getBytes(StandardCharsets.UTF_8), true); }

    private static void output(Path path, byte[] bytes, boolean append) throws IOException {
        var options = append ? java.util.Set.<java.nio.file.OpenOption>of(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
            : java.util.Set.<java.nio.file.OpenOption>of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        try (var parent = secureParent(path)) {
            try (var channel = parent.newByteChannel(path.getFileName(), options,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                writeBytes(channel, bytes);
            }
        } catch (UnsupportedOperationException ex) {
            if (System.getProperty("user.name").equals("root")) throw new IOException("Privileged output requires SecureDirectoryStream; use a supported Linux filesystem", ex);
            Path parent = outputParent(path), checked = parent.getRoot();
            for (Path component : parent) {
                checked = checked.resolve(component);
                if (Files.isSymbolicLink(checked)) throw new IOException("symlink output parent is not allowed");
            }
            try (var channel = Files.newByteChannel(parent.resolve(path.getFileName()), options,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) { writeBytes(channel, bytes); }
        }
    }

    private static void writeBytes(java.nio.channels.SeekableByteChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    // Pin each directory component so a concurrent symlink replacement cannot redirect writes.
    private static SecureDirectoryStream<Path> secureParent(Path path) throws IOException {
        Path parent = outputParent(path);
        DirectoryStream<Path> stream = Files.newDirectoryStream(parent.getRoot());
        if (!(stream instanceof SecureDirectoryStream<Path> current)) {
            stream.close(); throw new UnsupportedOperationException("secure directory operations are unavailable on this filesystem");
        }
        try {
            for (Path component : parent) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                current.close(); current = next;
            }
            return current;
        } catch (IOException | RuntimeException ex) { current.close(); throw ex; }
    }

    private static Path outputParent(Path path) {
        Path parent = path.toAbsolutePath().getParent().normalize();
        // macOS exposes these system directories through fixed root-level aliases.
        if (System.getProperty("os.name").equals("Mac OS X")) {
            for (String alias : java.util.List.of("/var", "/tmp", "/etc")) {
                Path prefix = Path.of(alias);
                if (parent.startsWith(prefix)) { parent = Path.of("/private" + alias).resolve(prefix.relativize(parent)); break; }
            }
        }
        return parent;
    }

    static void owner(Path path, UserPrincipal owner) throws IOException {
        try (var parent = secureParent(path)) {
            parent.getFileAttributeView(path.getFileName(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setOwner(owner);
        }
    }

    static void text(Path path, String text) throws IOException { write(path, text.getBytes(StandardCharsets.UTF_8)); }

    static Path contained(Path root, String relative) throws IOException {
        Path rel = Path.of(relative);
        if (rel.isAbsolute() || relative.isBlank() || rel.normalize().startsWith("..")) throw new IOException("BUNDLE_INVALID: unsafe relative path");
        Path normalizedRoot = root.toRealPath();
        Path resolved = normalizedRoot.resolve(rel).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IOException("BUNDLE_INVALID: path outside bundle");
        for (Path at = resolved; at != null && !at.equals(normalizedRoot); at = at.getParent()) {
            if (Files.isSymbolicLink(at)) throw new IOException("BUNDLE_INVALID: symlink in bundle");
        }
        return resolved;
    }

    static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private SafeFiles() { }
}
