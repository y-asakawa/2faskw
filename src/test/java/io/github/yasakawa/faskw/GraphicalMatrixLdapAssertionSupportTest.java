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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.naming.ldap.Control;
import javax.naming.NamingException;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixLdapAssertionSupportTest {
    @Test
    void createsACriticalRfc4528AndFilter() {
        final Map<String, String> equalities = new LinkedHashMap<>();
        equalities.put("ldap_state_version", "7");
        equalities.put("ldap_totp_registration_id", "registration-a");

        final Control control = GraphicalMatrixLdapAssertionSupport.control(equalities);

        assertEquals("1.3.6.1.1.12", control.getID());
        assertTrue(control.isCritical());
        final byte[] encoded = control.getEncodedValue();
        assertEquals(0xa0, encoded[0] & 0xff);
        final String printable = new String(encoded, StandardCharsets.ISO_8859_1);
        assertTrue(printable.contains("ldap_state_version"));
        assertTrue(printable.contains("registration-a"));
    }

    @Test
    void classifiesRfc4528AssertionFailureWithoutHidingOtherLdapErrors() {
        assertTrue(GraphicalMatrixLdapAssertionSupport.isAssertionFailed(
            new NamingException("[LDAP: error code 122 - assertion failed]")));
        assertTrue(!GraphicalMatrixLdapAssertionSupport.isAssertionFailed(
            new NamingException("[LDAP: error code 50 - insufficient access]")));
    }
}
