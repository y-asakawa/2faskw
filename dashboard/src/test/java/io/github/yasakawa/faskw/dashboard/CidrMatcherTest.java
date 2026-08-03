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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class CidrMatcherTest {

    @Test
    void matchesIpv4AndIpv6Networks() throws Exception {
        final CidrMatcher matcher = new CidrMatcher(
                "192.168.10.0/24,2001:db8:100::/48",
                "dashboard.auth.localAllowedCIDRs",
                true);

        assertTrue(matcher.matches(InetAddress.getByName("192.168.10.42")));
        assertFalse(matcher.matches(InetAddress.getByName("192.168.11.42")));
        assertTrue(matcher.matches(InetAddress.getByName("2001:db8:100::42")));
        assertFalse(matcher.matches(InetAddress.getByName("2001:db8:101::42")));
    }

    @Test
    void rejectsHostnamesAndUniversalNetworks() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CidrMatcher(
                        "localhost/32",
                        "dashboard.auth.localAllowedCIDRs",
                        true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CidrMatcher(
                        "0.0.0.0/0",
                        "dashboard.auth.localAllowedCIDRs",
                        true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CidrMatcher(
                        "::/0",
                        "dashboard.auth.localAllowedCIDRs",
                        true));
    }
}
