/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixAdoptionPolicySecurityTest {
    @TempDir Path home;
    private String xml(final String requirement, final String rule) {
        return "<AttributeFilterPolicyGroup xmlns='urn:mace:shibboleth:2.0:afp' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xmlns:afp='urn:mace:shibboleth:2.0:afp' xmlns:evil='urn:other'>"
            + "<AttributeFilterPolicy id='legacy'><PolicyRequirementRule xsi:type='" + requirement + "' value='https://sp.example.test'/><AttributeRule attributeID='uid'>"
            + rule + "</AttributeRule></AttributeFilterPolicy></AttributeFilterPolicyGroup>";
    }

    @Test void acceptsResolvedQNameButRejectsValueRestrictionsDenyAndUnknownNamespaces() throws Exception {
        final Path file = home.resolve("filter.xml");
        Files.writeString(file, xml("afp:Requester", "<PermitValueRule xsi:type='afp:ANY'/>"));
        assertEquals("uid", GraphicalMatrixSpXmlConfig.planAttributeAdoption(file, "https://sp.example.test").profile());
        for (final String rule : java.util.List.of("<PermitValueRule xsi:type='ValueRegex' regex='^staff-.*$'/>",
                "<PermitValueRule xsi:type='ANY'/><DenyValueRule xsi:type='ANY'/>",
                "<PermitValueRule xsi:type='evil:ANY'/>", "<PermitValueRule xsi:type='ANY' ref='other'/>",
                "<PermitValueRule xsi:type='ANY'/><PermitValueRule xsi:type='ANY'/>")) {
            Files.writeString(file, xml("Requester", rule));
            final String before = Files.readString(file);
            assertThrows(IllegalArgumentException.class, () -> GraphicalMatrixSpXmlConfig.planAttributeAdoption(file, "https://sp.example.test"));
            assertEquals(before, Files.readString(file));
        }
        Files.writeString(file, xml("evil:Requester", "<PermitValueRule xsi:type='ANY'/>"));
        assertThrows(IllegalArgumentException.class, () -> GraphicalMatrixSpXmlConfig.planAttributeAdoption(file, "https://sp.example.test"));
    }

    @Test void refusesChangedSourceBeforeRemovingPlannedPolicy() throws Exception {
        final Path file = home.resolve("filter.xml");
        Files.writeString(file, xml("Requester", "<PermitValueRule xsi:type='ANY'/>"));
        final var plan = GraphicalMatrixSpXmlConfig.planAttributeAdoption(file, "https://sp.example.test");
        Files.writeString(file, xml("Requester", "<PermitValueRule xsi:type='ValueRegex' regex='^staff-.*$'/>"));
        final String changed = Files.readString(file);
        assertThrows(IllegalStateException.class, () -> GraphicalMatrixSpXmlConfig.removePlannedAttributePolicy(file, "https://sp.example.test", plan));
        assertEquals(changed, Files.readString(file));
    }
}
