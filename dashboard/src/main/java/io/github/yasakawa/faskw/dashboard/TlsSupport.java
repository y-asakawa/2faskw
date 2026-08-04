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
package io.github.yasakawa.faskw.dashboard;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class TlsSupport {

    private TlsSupport() {
    }

    static SSLContext serverContext(
            final Path keyStorePath,
            final Path keyStorePasswordFile,
            final Path trustStorePath,
            final Path trustStorePasswordFile) throws Exception {
        return context(
                keyStorePath,
                readPassword(keyStorePasswordFile),
                trustStorePath,
                readPassword(trustStorePasswordFile));
    }

    static SSLContext clientContext(
            final Path keyStorePath,
            final Path keyStorePasswordFile,
            final Path trustStorePath,
            final Path trustStorePasswordFile) throws Exception {
        return context(
                keyStorePath,
                readPassword(keyStorePasswordFile),
                trustStorePath,
                readPassword(trustStorePasswordFile));
    }

    private static SSLContext context(
            final Path keyStorePath,
            final char[] keyPassword,
            final Path trustStorePath,
            final char[] trustPassword) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, keyPassword);
        }
        final KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, keyPassword);

        final KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(trustStorePath)) {
            trustStore.load(input, trustPassword);
        }
        final TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);

        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    private static char[] readPassword(final Path path) throws Exception {
        final String value = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("TLS password file must contain one non-empty line");
        }
        return value.toCharArray();
    }
}
