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
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class GraphicalMatrixTotpSeedStorage {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;

    private final String mode;
    private final String configuredMode;
    private final boolean productionMode;
    private final String keyword;
    private final byte[] aesKey;

    private GraphicalMatrixTotpSeedStorage(final String mode, final String configuredMode,
            final boolean productionMode, final String keyword, final byte[] aesKey) {
        this.mode = mode;
        this.configuredMode = configuredMode;
        this.productionMode = productionMode;
        this.keyword = keyword;
        this.aesKey = aesKey != null ? aesKey.clone() : new byte[0];
    }

    public static GraphicalMatrixTotpSeedStorage load(final String idpHome) {
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

        final String configuredMode = normalizeMode(property(props, "graphicalmatrix.totp.seed.storage", "auto"));
        final String sequenceMode = normalizeSequenceMode(property(props, "graphicalmatrix.sequence.storage", "auto"));
        final String mode = resolveMode(configuredMode, sequenceMode);
        final boolean productionMode = booleanProperty(props, "graphicalmatrix.productionMode", false);
        final String keyword;
        final String aesKeyText;
        if ("keyword".equals(mode)) {
            keyword = firstSecret(props,
                "graphicalmatrix.totp.seed.keyword", "graphicalmatrix.totp.seed.keywordFile",
                "graphicalmatrix.sequence.keyword", "graphicalmatrix.sequence.keywordFile");
            aesKeyText = "";
        } else if ("aes-gcm".equals(mode)) {
            keyword = "";
            aesKeyText = firstSecret(props,
                "graphicalmatrix.totp.seed.aesKey", "graphicalmatrix.totp.seed.aesKeyFile",
                "graphicalmatrix.sequence.aesKey", "graphicalmatrix.sequence.aesKeyFile");
        } else {
            keyword = "";
            aesKeyText = "";
        }

        return new GraphicalMatrixTotpSeedStorage(
            mode,
            configuredMode,
            productionMode,
            keyword,
            aesKeyText.isEmpty() ? new byte[0] : keyBytes(aesKeyText)
        );
    }

    public String mode() {
        return mode;
    }

    public String configuredMode() {
        return configuredMode;
    }

    public String encode(final String seed) {
        final String plain = trim(seed);
        if (plain.isEmpty()) {
            return "";
        }
        try {
            if ("plaintext".equals(mode)) {
                rejectProductionPlaintext();
                return plain;
            }
            if ("keyword".equals(mode)) {
                require(keyword, "graphicalmatrix.totp.seed.keyword/File or graphicalmatrix.sequence.keyword/File");
                final byte[] salt = random(SALT_BYTES);
                final byte[] iv = random(GCM_IV_BYTES);
                final byte[] key = deriveKeywordKey(keyword, salt);
                return "totpkw1:" + b64(salt) + ":" + b64(iv) + ":" + b64(aesGcmEncrypt(key, iv, plain));
            }
            if ("aes-gcm".equals(mode)) {
                require(aesKey, "graphicalmatrix.totp.seed.aesKey/File or graphicalmatrix.sequence.aesKey/File");
                final byte[] iv = random(GCM_IV_BYTES);
                return "totpaesgcm1:" + b64(iv) + ":" + b64(aesGcmEncrypt(aesKey, iv, plain));
            }
            if ("unconfigured-hash".equals(mode)) {
                throw new IllegalStateException(
                    "graphicalmatrix.totp.seed.storage=auto cannot inherit hash sequence storage; "
                    + "set graphicalmatrix.totp.seed.storage to aes-gcm or keyword.");
            }
            throw new IllegalStateException("Unsupported TOTP seed storage mode: " + mode);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encode TOTP seed", ex);
        }
    }

    public String decode(final String stored) {
        return decodeInternal(stored, false);
    }

    public String decodeForMigration(final String stored) {
        return decodeInternal(stored, true);
    }

    public String storedMode(final String stored) {
        final String value = trim(stored);
        if (value.isEmpty()) {
            return "empty";
        }
        if (value.startsWith("totpkw1:")) {
            return "keyword";
        }
        if (value.startsWith("totpaesgcm1:")) {
            return "aes-gcm";
        }
        return "plaintext";
    }

    public boolean plaintextAllowed() {
        return !productionMode;
    }

    private String decodeInternal(final String stored, final boolean allowPlaintextForMigration) {
        final String value = trim(stored);
        if (value.isEmpty()) {
            return "";
        }
        try {
            if (value.startsWith("totpkw1:")) {
                require(keyword, "graphicalmatrix.totp.seed.keyword/File or graphicalmatrix.sequence.keyword/File");
                final String[] parts = value.split(":", 4);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Invalid keyword TOTP seed payload.");
                }
                return aesGcmDecrypt(deriveKeywordKey(keyword, b64d(parts[1])), b64d(parts[2]), b64d(parts[3]));
            }
            if (value.startsWith("totpaesgcm1:")) {
                require(aesKey, "graphicalmatrix.totp.seed.aesKey/File or graphicalmatrix.sequence.aesKey/File");
                final String[] parts = value.split(":", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid AES-GCM TOTP seed payload.");
                }
                return aesGcmDecrypt(aesKey, b64d(parts[1]), b64d(parts[2]));
            }
            if (!allowPlaintextForMigration) {
                rejectProductionPlaintext();
            }
            return value;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decode TOTP seed", ex);
        }
    }

    private void rejectProductionPlaintext() {
        if (productionMode) {
            throw new IllegalStateException(
                "Plaintext TOTP seed storage is rejected when graphicalmatrix.productionMode=true.");
        }
    }

    private static String resolveMode(final String configuredMode, final String sequenceMode) {
        if (!"auto".equals(configuredMode)) {
            if ("hash".equals(configuredMode)) {
                throw new IllegalArgumentException("graphicalmatrix.totp.seed.storage does not support hash.");
            }
            return configuredMode;
        }
        if ("aes-gcm".equals(sequenceMode) || "keyword".equals(sequenceMode)
                || "plaintext".equals(sequenceMode)) {
            return sequenceMode;
        }
        if ("hash".equals(sequenceMode)) {
            return "unconfigured-hash";
        }
        throw new IllegalArgumentException("Unsupported graphicalmatrix.sequence.storage: " + sequenceMode);
    }

    private static String normalizeMode(final String mode) {
        final String value = trim(mode).toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "auto".equals(value)) {
            return "auto";
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
        throw new IllegalArgumentException("Unsupported graphicalmatrix.totp.seed.storage: " + mode);
    }

    private static String normalizeSequenceMode(final String mode) {
        final String value = trim(mode).toLowerCase(Locale.ROOT);
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

    private static String firstSecret(final Properties props, final String directKey, final String fileKey,
            final String fallbackDirectKey, final String fallbackFileKey) {
        final String direct = property(props, directKey, "");
        if (!direct.isEmpty()) {
            return direct;
        }
        final String fileValue = fileSecret(props, fileKey);
        if (!fileValue.isEmpty()) {
            return fileValue;
        }
        final String fallbackDirect = property(props, fallbackDirectKey, "");
        if (!fallbackDirect.isEmpty()) {
            return fallbackDirect;
        }
        return fileSecret(props, fallbackFileKey);
    }

    private static String fileSecret(final Properties props, final String fileKey) {
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

    private static boolean booleanProperty(final Properties props, final String key, final boolean defaultValue) {
        final String value = property(props, key, "");
        if (value.isEmpty()) {
            return defaultValue;
        }
        final String normalized = value.toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
            || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static String property(final Properties props, final String key, final String defaultValue) {
        final String value = props.getProperty(key);
        return value != null && !value.trim().isEmpty() ? value.trim() : defaultValue;
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

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
