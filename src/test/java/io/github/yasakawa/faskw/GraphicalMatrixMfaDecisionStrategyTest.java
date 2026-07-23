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
