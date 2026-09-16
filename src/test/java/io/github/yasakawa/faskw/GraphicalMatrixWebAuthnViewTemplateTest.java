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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixWebAuthnViewTemplateTest {
    @Test
    void distinguishesCredentialRegistrationFromNormalAuthentication() throws Exception {
        final String template = Files.readString(
            Path.of("views/webauthn/webauthn-authn.vm"));

        assertTrue(template.contains(
            "http://shibboleth.net/ns/profiles/admin/webauthn/register-credential"));
        assertTrue(template.contains("WebAuthn credentialの追加"));
        assertTrue(template.contains("既存キーで本人確認してください。"));
        assertTrue(template.contains("登録済みキーで本人確認する"));
        assertTrue(template.contains("#if (!$credentialRegistrationRequester)"));
        assertTrue(template.contains(
            "$request.getContextPath()/profile/admin/webauthn-registration"));
        assertFalse(template.contains("?reg=inline"));
    }
}
