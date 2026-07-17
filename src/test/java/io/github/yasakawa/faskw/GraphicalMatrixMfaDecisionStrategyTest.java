package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphicalMatrixMfaDecisionStrategyTest {
    @Test
    void matchesCidrOnlyForTheConfiguredRelyingParty() {
        final String rules = "https://sp1.example.org/shibboleth|192.168.10.0/24;"
            + "https://sp2.example.org/shibboleth|10.20.0.0/16,10.21.0.0/16";

        assertTrue(GraphicalMatrixMfaDecisionStrategy.spCidrMatches(
            rules, "https://sp1.example.org/shibboleth", "192.168.10.20"));
        assertTrue(GraphicalMatrixMfaDecisionStrategy.spCidrMatches(
            rules, "https://sp2.example.org/shibboleth", "10.21.12.34"));
        assertFalse(GraphicalMatrixMfaDecisionStrategy.spCidrMatches(
            rules, "https://sp2.example.org/shibboleth", "192.168.10.20"));
        assertFalse(GraphicalMatrixMfaDecisionStrategy.spCidrMatches(
            rules, "https://sp1.example.org/shibboleth", "192.168.11.20"));
    }

    @Test
    void ignoresMalformedRulesAndNonIpv4Cidrs() {
        assertFalse(GraphicalMatrixMfaDecisionStrategy.spCidrMatches(
            "invalid;https://sp1.example.org/shibboleth|not-a-cidr",
            "https://sp1.example.org/shibboleth", "192.168.10.20"));
        assertFalse(GraphicalMatrixMfaDecisionStrategy.spCidrMatches(
            "https://sp1.example.org/shibboleth|2001:db8::/32",
            "https://sp1.example.org/shibboleth", "2001:db8::1"));
    }
}
