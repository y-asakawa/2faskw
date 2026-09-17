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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.naming.NamingException;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.BasicControl;
import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;

/** RFC 4528 LDAP Assertion Control support for atomic enrollment updates. */
final class GraphicalMatrixLdapAssertionSupport {
    static final String ASSERTION_CONTROL_OID = "1.3.6.1.1.12";

    private GraphicalMatrixLdapAssertionSupport() {
    }

    static Control control(final Map<String, String> equalities) {
        if (equalities == null || equalities.isEmpty()) {
            throw new IllegalArgumentException("LDAP assertion requires at least one equality.");
        }
        final ByteArrayOutputStream filters = new ByteArrayOutputStream();
        for (final Map.Entry<String, String> equality : equalities.entrySet()) {
            final byte[] attribute = required(equality.getKey(), "attribute");
            final byte[] value = required(equality.getValue(), "value");
            final ByteArrayOutputStream content = new ByteArrayOutputStream();
            writeTlv(content, 0x04, attribute);
            writeTlv(content, 0x04, value);
            writeTlv(filters, 0xa3, content.toByteArray());
        }
        final ByteArrayOutputStream assertion = new ByteArrayOutputStream();
        writeTlv(assertion, 0xa0, filters.toByteArray());
        return new BasicControl(ASSERTION_CONTROL_OID, true, assertion.toByteArray());
    }

    static void modify(final LdapContext context, final String dn,
            final Map<String, String> equalities, final ModificationItem... items)
            throws NamingException {
        final Control[] previous = context.getRequestControls();
        context.setRequestControls(append(previous, control(equalities)));
        try {
            context.modifyAttributes(dn, items);
        } finally {
            context.setRequestControls(previous);
        }
    }

    static boolean isAssertionFailed(final NamingException exception) {
        final String message = exception != null && exception.getMessage() != null
            ? exception.getMessage().toLowerCase(java.util.Locale.ROOT) : "";
        return message.contains("error code 122") || message.contains("assertion failed");
    }

    private static Control[] append(final Control[] controls, final Control assertion) {
        final int count = controls == null ? 0 : controls.length;
        final Control[] combined = new Control[count + 1];
        if (count > 0) {
            System.arraycopy(controls, 0, combined, 0, count);
        }
        combined[count] = assertion;
        return combined;
    }

    private static byte[] required(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LDAP assertion " + name + " is required.");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeTlv(final ByteArrayOutputStream out, final int tag,
            final byte[] value) {
        out.write(tag);
        writeLength(out, value.length);
        out.writeBytes(value);
    }

    private static void writeLength(final ByteArrayOutputStream out, final int length) {
        if (length < 128) {
            out.write(length);
            return;
        }
        int bytes = 0;
        int value = length;
        while (value > 0) {
            bytes++;
            value >>>= 8;
        }
        out.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            out.write((length >>> shift) & 0xff);
        }
    }
}
