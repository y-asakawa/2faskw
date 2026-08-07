/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixSpAccessXmlConfigTest {
    @TempDir
    private Path temporary;

    @Test
    void replacesStockConditionAndPreservesExistingPostAuthenticationFlows()
            throws Exception {
        final Path context = temporary.resolve("context-check-intercept-config.xml");
        final Path relying = temporary.resolve("relying-party.xml");
        Files.writeString(context, contextConfig("""
            <bean id="shibboleth.context-check.Condition"
                  parent="shibboleth.Conditions.AND">
              <constructor-arg><list>
                <bean parent="shibboleth.Conditions.RelyingPartyId"/>
                <bean parent="shibboleth.Conditions.SimpleAttribute">
                  <property name="attributeValueMap">
                    <map><entry key="eppn"><list><value>*</value></list></entry></map>
                  </property>
                </bean>
              </list></constructor-arg>
            </bean>
            """));
        Files.writeString(relying, contextConfig("""
            <bean id="shibboleth.DefaultRelyingParty" parent="RelyingParty">
              <property name="profileConfigurations"><list>
                <ref bean="SAML2.SSO"/>
                <bean parent="SAML2.SSO">
                  <property name="postAuthenticationFlows">
                    <list><value>terms-of-use</value></list>
                  </property>
                </bean>
              </list></property>
            </bean>
            """));

        GraphicalMatrixSpAccessXmlConfig.initializeContextCheck(context);
        GraphicalMatrixSpAccessXmlConfig.initializeRelyingParty(relying);
        GraphicalMatrixSpAccessXmlConfig.initializeRelyingParty(relying);

        final GraphicalMatrixSpAccessXmlConfig.Inspection inspection =
            GraphicalMatrixSpAccessXmlConfig.inspect(context, relying);
        assertTrue(inspection.managedFunction());
        assertEquals(2, inspection.saml2Profiles());
        assertEquals(2, inspection.contextCheckProfiles());
        assertTrue(Files.readString(relying).contains("terms-of-use"));
    }

    @Test
    void refusesAnExistingCustomContextCheckFunction() throws Exception {
        final Path context = temporary.resolve("context.xml");
        final Path relying = temporary.resolve("relying.xml");
        Files.writeString(context, contextConfig("""
            <bean id="shibboleth.context-check.Function" class="example.CustomFunction"/>
            """));
        Files.writeString(relying, contextConfig("<ref bean=\"SAML2.SSO\"/>"));
        assertThrows(IllegalStateException.class,
            () -> GraphicalMatrixSpAccessXmlConfig.initializeContextCheck(context));
    }

    private static String contextConfig(final String body) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:p="http://www.springframework.org/schema/p"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            %s
            </beans>
            """.formatted(body);
    }
}
