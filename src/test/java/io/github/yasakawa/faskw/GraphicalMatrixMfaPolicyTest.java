package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class GraphicalMatrixMfaPolicyTest {
    private static final String SENSITIVE_SP = "https://sp-sensitive.example.org/shibboleth";
    private static final String NORMAL_SP = "https://sp-normal.example.org/shibboleth";

    @Test
    void forceSpOverridesInternalNetworkWithDefaultOrder() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.forceSPs", SENSITIVE_SP,
            "graphicalmatrix.mfa.bypassCIDRs", "192.168.0.0/24",
            "graphicalmatrix.mfa.default", "require");

        assertDecision(policy, SENSITIVE_SP, "192.168.0.1",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "forceSPs");
        assertDecision(policy, NORMAL_SP, "192.168.0.1",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "bypassNetwork");
        assertDecision(policy, NORMAL_SP, "203.0.113.10",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "default");
    }

    @Test
    void customOrderCanPlaceNetworkBypassBeforeForceSp() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.policyOrder",
                "bypassNetwork,forceSPs,bypassSPs,bypassSpCidrs,requiredSPs,default",
            "graphicalmatrix.mfa.forceSPs", SENSITIVE_SP,
            "graphicalmatrix.mfa.bypassCIDRs", "192.168.0.0/24");

        assertDecision(policy, SENSITIVE_SP, "192.168.0.1",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "bypassNetwork");
    }

    @Test
    void existingBypassRulesRemainAheadOfRequiredSpByDefault() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.requiredSPs", SENSITIVE_SP,
            "graphicalmatrix.mfa.bypassCIDRs", "192.168.0.0/24");

        assertDecision(policy, SENSITIVE_SP, "192.168.0.1",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "bypassNetwork");
        assertDecision(policy, SENSITIVE_SP, "203.0.113.10",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "requiredSPs");
        assertDecision(policy, NORMAL_SP, "203.0.113.10",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "requiredSPs");
    }

    @Test
    void spSpecificCidrRuleOnlyBypassesMatchingSpAndNetwork() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.bypassSpCidrs", NORMAL_SP + "|10.10.0.0/16");

        assertDecision(policy, NORMAL_SP, "10.10.20.30",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "bypassSpCidrs");
        assertDecision(policy, SENSITIVE_SP, "10.10.20.30",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "default");
    }

    @Test
    void explicitIpBypassUsesStrictIpv4Matching() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.bypassIPs", "192.0.2.65");

        assertDecision(policy, NORMAL_SP, "192.0.2.65",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "bypassNetwork");
        assertDecision(policy, NORMAL_SP, "192.0.2.66",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "default");
    }

    @Test
    void missingOrderUsesTheDocumentedDefaultOrder() {
        final GraphicalMatrixMfaPolicy policy = parse();

        assertEquals("forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default",
            policy.orderText());
        assertDecision(policy, NORMAL_SP, "203.0.113.10",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "default");
    }

    @Test
    void defaultBypassIsAppliedOnlyWhenNoEarlierRuleDecides() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.forceSPs", SENSITIVE_SP,
            "graphicalmatrix.mfa.default", "bypass");

        assertDecision(policy, SENSITIVE_SP, "203.0.113.10",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "forceSPs");
        assertDecision(policy, NORMAL_SP, "203.0.113.10",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "default");
    }

    @Test
    void ipv6ClientDoesNotMatchIpv4BypassRules() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.bypassCIDRs", "0.0.0.0/0");

        assertDecision(policy, NORMAL_SP, "2001:db8::1",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "default");
    }

    @Test
    void explicitIpv6BypassMatchesEquivalentAddressText() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.bypassIPs", "2001:db8::1");

        assertDecision(policy, NORMAL_SP, "2001:0db8:0:0:0:0:0:1",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "bypassNetwork");
        assertDecision(policy, NORMAL_SP, "2001:db8::2",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "default");
    }

    @Test
    void requiredSpCanBePlacedBeforeBypassRules() {
        final GraphicalMatrixMfaPolicy policy = parse(
            "graphicalmatrix.mfa.policyOrder",
                "forceSPs,requiredSPs,bypassSPs,bypassSpCidrs,bypassNetwork,default",
            "graphicalmatrix.mfa.requiredSPs", SENSITIVE_SP,
            "graphicalmatrix.mfa.bypassCIDRs", "192.168.0.0/24");

        assertDecision(policy, SENSITIVE_SP, "192.168.0.1",
            GraphicalMatrixMfaPolicy.Outcome.REQUIRE, "requiredSPs");
        assertDecision(policy, NORMAL_SP, "192.168.0.1",
            GraphicalMatrixMfaPolicy.Outcome.BYPASS, "requiredSPs");
    }

    @Test
    void rejectsUnknownDuplicateMissingAndMisplacedRules() {
        assertInvalidOrder("forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,unknown,default");
        assertInvalidOrder("forceSPs,bypassSPs,bypassSPs,bypassSpCidrs,bypassNetwork,default");
        assertInvalidOrder("forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,default");
        assertInvalidOrder("forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,default,requiredSPs");
    }

    @Test
    void rejectsMalformedPolicyValues() {
        assertThrows(IllegalArgumentException.class, () -> parse(
            "graphicalmatrix.mfa.default", "allow"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "graphicalmatrix.mfa.bypassIPs", "host.example.org"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "graphicalmatrix.mfa.bypassCIDRs", "192.168.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "graphicalmatrix.mfa.bypassSpCidrs", SENSITIVE_SP + "|not-a-cidr"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "graphicalmatrix.mfa.useForwardedFor", "yes"));
    }

    private static void assertInvalidOrder(final String order) {
        assertThrows(IllegalArgumentException.class, () -> parse(
            "graphicalmatrix.mfa.policyOrder", order));
    }

    private static void assertDecision(final GraphicalMatrixMfaPolicy policy,
            final String sp, final String ip, final GraphicalMatrixMfaPolicy.Outcome outcome,
            final String rule) {
        final GraphicalMatrixMfaPolicy.Decision decision = policy.evaluate(sp, ip);
        assertEquals(outcome, decision.outcome());
        assertEquals(rule, decision.rule());
    }

    private static GraphicalMatrixMfaPolicy parse(final String... values) {
        final Properties properties = new Properties();
        for (int i = 0; i < values.length; i += 2) {
            properties.setProperty(values[i], values[i + 1]);
        }
        return GraphicalMatrixMfaPolicy.parse(properties);
    }
}
