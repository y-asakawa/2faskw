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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class GraphicalMatrixSequenceStorage {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;

    private final String mode;
    private final String keyword;
    private final byte[] aesKey;
    private final byte[] pepper;

    private GraphicalMatrixSequenceStorage(final String mode, final String keyword,
            final byte[] aesKey, final byte[] pepper) {
        this.mode = mode;
        this.keyword = keyword;
        this.aesKey = aesKey != null ? aesKey.clone() : new byte[0];
        this.pepper = pepper != null ? pepper.clone() : new byte[0];
    }

    public static GraphicalMatrixSequenceStorage load(final String idpHome) {
        final Properties props = new Properties();
        final String overridePath = System.getenv("GRAPHICAL_PROPERTIES") != null
            ? System.getenv("GRAPHICAL_PROPERTIES").trim()
            : "";
        final Path path = overridePath.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "graphicalmatrix.properties")
            : Path.of(overridePath);
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to load GraphicalMatrix properties: " + path, ex);
            }
        }

        final String rawMode = property(props, "graphicalmatrix.sequence.storage", "auto");
        final String mode = normalizeMode(rawMode);
        final String keyword;
        final String aesKeyText;
        final String pepperText;
        if ("keyword".equals(mode)) {
            keyword = secret(props, "graphicalmatrix.sequence.keyword",
                "graphicalmatrix.sequence.keywordFile");
            aesKeyText = "";
            pepperText = "";
        } else if ("aes-gcm".equals(mode)) {
            keyword = "";
            aesKeyText = secret(props, "graphicalmatrix.sequence.aesKey",
                "graphicalmatrix.sequence.aesKeyFile");
            pepperText = "";
        } else if ("hash".equals(mode)) {
            keyword = "";
            aesKeyText = "";
            pepperText = secret(props, "graphicalmatrix.sequence.pepper",
                "graphicalmatrix.sequence.pepperFile");
        } else {
            keyword = "";
            aesKeyText = "";
            pepperText = "";
        }

        return new GraphicalMatrixSequenceStorage(
            mode,
            keyword,
            aesKeyText.isEmpty() ? new byte[0] : keyBytes(aesKeyText),
            pepperText.isEmpty() ? new byte[0] : pepperText.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String mode() {
        return mode;
    }

    public String encode(final List<String> sequence, final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed) {
        final String plain = String.join(",", sequence);
        try {
            if ("plaintext".equals(mode)) {
                return plain;
            }
            if ("keyword".equals(mode)) {
                require(keyword, "graphicalmatrix.sequence.keyword or keywordFile");
                final byte[] salt = random(SALT_BYTES);
                final byte[] iv = random(GCM_IV_BYTES);
                final byte[] key = deriveKeywordKey(keyword, salt);
                return "kw1:" + b64(salt) + ":" + b64(iv) + ":" + b64(aesGcmEncrypt(key, iv, plain));
            }
            if ("aes-gcm".equals(mode)) {
                require(aesKey, "graphicalmatrix.sequence.aesKey or aesKeyFile");
                final byte[] iv = random(GCM_IV_BYTES);
                return "aesgcm1:" + b64(iv) + ":" + b64(aesGcmEncrypt(aesKey, iv, plain));
            }
            if ("hash".equals(mode)) {
                require(pepper, "graphicalmatrix.sequence.pepper or pepperFile");
                final byte[] salt = random(SALT_BYTES);
                final String canonical = canonical(sequence, orderedSelectionRequired, duplicateSelectionsAllowed);
                return "hsp1:" + sequence.size() + ":" + b64(salt) + ":" + b64(hmac(salt, canonical));
            }
            throw new IllegalStateException("Unsupported sequence storage mode: " + mode);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encode GraphicalMatrix sequence", ex);
        }
    }

    public List<String> displayTokens(final String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return new ArrayList<>();
        }
        if (isHash(stored)) {
            return new ArrayList<>();
        }
        return decode(stored);
    }

    public String readableSequence(final String stored) {
        if (stored == null || stored.trim().isEmpty() || isHash(stored)) {
            return stored;
        }
        return String.join(",", decode(stored));
    }

    public boolean recoverable(final String stored) {
        return !isHash(stored);
    }

    public String storedMode(final String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return "empty";
        }
        if (stored.startsWith("kw1:")) {
            return "keyword";
        }
        if (stored.startsWith("aesgcm1:")) {
            return "aes-gcm";
        }
        if (stored.startsWith("hsp1:")) {
            return "hash";
        }
        return "plaintext";
    }

    public boolean acceptedForRuntime(final String stored) {
        final String storedMode = storedMode(stored);
        return !"empty".equals(storedMode) && mode.equals(storedMode);
    }

    public int count(final String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return 0;
        }
        if (isHash(stored)) {
            final String[] parts = stored.split(":", 4);
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    return 0;
                }
            }
            return 0;
        }
        return decode(stored).size();
    }

    public boolean usable(final String stored, final GraphicalMatrixConfig config) {
        if (!acceptedForRuntime(stored)) {
            return false;
        }
        if (isHash(stored)) {
            return count(stored) == config.getChoiceCount();
        }
        try {
            config.validateSequence(decode(stored));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean matches(final String stored, final List<String> selected,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed) {
        if (!acceptedForRuntime(stored)) {
            return false;
        }
        if (isHash(stored)) {
            require(pepper, "graphicalmatrix.sequence.pepper or pepperFile");
            final String[] parts = stored.split(":", 4);
            if (parts.length != 4) {
                return false;
            }
            final int expectedCount;
            try {
                expectedCount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (selected.size() != expectedCount) {
                return false;
            }
            final byte[] salt;
            final byte[] expectedDigest;
            try {
                salt = b64d(parts[2]);
                expectedDigest = b64d(parts[3]);
            } catch (IllegalArgumentException ex) {
                return false;
            }
            final byte[] actual = hmac(salt,
                canonical(selected, orderedSelectionRequired, duplicateSelectionsAllowed));
            return MessageDigest.isEqual(expectedDigest, actual);
        }
        return matched(decode(stored), selected, orderedSelectionRequired, duplicateSelectionsAllowed);
    }

    private List<String> decode(final String stored) {
        final String value = stored != null ? stored.trim() : "";
        if (value.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            if (value.startsWith("kw1:")) {
                require(keyword, "graphicalmatrix.sequence.keyword or keywordFile");
                final String[] parts = value.split(":", 4);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Invalid keyword sequence payload.");
                }
                final byte[] salt = b64d(parts[1]);
                final byte[] iv = b64d(parts[2]);
                final byte[] cipher = b64d(parts[3]);
                return GraphicalMatrixSupport.csv(aesGcmDecrypt(deriveKeywordKey(keyword, salt), iv, cipher));
            }
            if (value.startsWith("aesgcm1:")) {
                require(aesKey, "graphicalmatrix.sequence.aesKey or aesKeyFile");
                final String[] parts = value.split(":", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid AES-GCM sequence payload.");
                }
                return GraphicalMatrixSupport.csv(aesGcmDecrypt(aesKey, b64d(parts[1]), b64d(parts[2])));
            }
            if (isHash(value)) {
                throw new IllegalStateException("Hash sequence storage is not recoverable.");
            }
            return GraphicalMatrixSupport.csv(value);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decode GraphicalMatrix sequence", ex);
        }
    }

    private static String normalizeMode(final String mode) {
        final String value = mode != null ? mode.trim().toLowerCase(Locale.ROOT) : "";
        if (value.isEmpty() || "auto".equals(value)) {
            return "hash";
        }
        if ("plain".equals(value) || "plaintext".equals(value)) {
            return "plaintext";
        }
        if ("keyword".equals(value) || "common-keyword".equals(value) || "common_keyword".equals(value)) {
            return "keyword";
        }
        if ("aes".equals(value) || "aes-gcm".equals(value) || "aes_gcm".equals(value)) {
            return "aes-gcm";
        }
        if ("hash".equals(value) || "hash-salt-pepper".equals(value) || "hash_salt_pepper".equals(value)) {
            return "hash";
        }
        throw new IllegalArgumentException("Unsupported graphicalmatrix.sequence.storage: " + mode);
    }

    private static boolean isHash(final String value) {
        return value != null && value.startsWith("hsp1:");
    }

    private static String canonical(final List<String> sequence, final boolean orderedSelectionRequired,
            final boolean duplicateSelectionsAllowed) {
        if (orderedSelectionRequired) {
            return String.join(",", sequence);
        }
        final List<String> copy = new ArrayList<>(sequence);
        Collections.sort(copy);
        return String.join(",", copy);
    }

    private static boolean matched(final List<String> expected, final List<String> selected,
            final boolean orderedSelectionRequired, final boolean duplicateSelectionsAllowed) {
        if (orderedSelectionRequired) {
            return expected.equals(selected);
        }
        if (duplicateSelectionsAllowed) {
            return multiset(expected).equals(multiset(selected));
        }
        return new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(selected));
    }

    private static java.util.Map<String, Integer> multiset(final List<String> values) {
        final java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (final String value : values) {
            out.put(value, out.getOrDefault(value, 0) + 1);
        }
        return out;
    }

    private static byte[] aesGcmEncrypt(final byte[] key, final byte[] iv, final String plain) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static String aesGcmDecrypt(final byte[] key, final byte[] iv, final byte[] cipherText) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    private static byte[] deriveKeywordKey(final String keyword, final byte[] salt) throws Exception {
        final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        final KeySpec spec = new PBEKeySpec(keyword.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        return factory.generateSecret(spec).getEncoded();
    }

    private byte[] hmac(final byte[] salt, final String canonicalSequence) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            mac.update(salt);
            mac.update((byte) ':');
            mac.update(canonicalSequence.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash GraphicalMatrix sequence", ex);
        }
    }

    private static byte[] random(final int size) {
        final byte[] out = new byte[size];
        RNG.nextBytes(out);
        return out;
    }

    private static String b64(final byte[] value) {
        return B64.encodeToString(value);
    }

    private static byte[] b64d(final String value) {
        return B64D.decode(value);
    }

    private static byte[] keyBytes(final String value) {
        try {
            final byte[] decoded = Base64.getDecoder().decode(value);
            if (validAesKeyLength(decoded.length)) {
                return decoded;
            }
        } catch (IllegalArgumentException ex) {
            // Try URL-safe base64 and raw key below.
        }
        try {
            final byte[] decoded = B64D.decode(value);
            if (validAesKeyLength(decoded.length)) {
                return decoded;
            }
        } catch (IllegalArgumentException ex) {
            // Try raw key below.
        }
        final byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (validAesKeyLength(raw.length)) {
            return raw;
        }
        throw new IllegalArgumentException(
            "AES-GCM key must be 16, 24, or 32 bytes, preferably base64 encoded.");
    }

    private static boolean validAesKeyLength(final int length) {
        return length == 16 || length == 24 || length == 32;
    }

    private static String property(final Properties props, final String key, final String defaultValue) {
        final String value = props.getProperty(key);
        return value != null && !value.trim().isEmpty() ? value.trim() : defaultValue;
    }

    private static String secret(final Properties props, final String directKey, final String fileKey) {
        final String direct = property(props, directKey, "");
        if (!direct.isEmpty()) {
            return direct;
        }
        final String file = property(props, fileKey, "");
        if (file.isEmpty()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read secret file: " + file, ex);
        }
    }

    private static void require(final String value, final String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing " + label);
        }
    }

    private static void require(final byte[] value, final String label) {
        if (value == null || value.length == 0) {
            throw new IllegalStateException("Missing " + label);
        }
    }
}
