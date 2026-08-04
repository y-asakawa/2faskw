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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PrivacyFilterTest {

    @Test
    void defaultDropModeRemovesIdentifiers() {
        final PrivacyFilter filter = new PrivacyFilter(
                PrivacyFilter.UserMode.DROP, PrivacyFilter.IpMode.DROP, null);

        assertNull(filter.userRef("user001"));
        assertNull(filter.sourceNetwork("192.0.2.25"));
    }

    @Test
    void plainModePreservesSafeUserIds() {
        final PrivacyFilter filter = new PrivacyFilter(
                PrivacyFilter.UserMode.PLAIN, PrivacyFilter.IpMode.PLAIN, null);

        assertEquals("user_001@example.org", filter.userRef("user_001@example.org"));
        assertEquals("192.0.2.25", filter.sourceNetwork("192.0.2.25"));
    }

    @Test
    void hmacAndPrefixModesAreStableAndDoNotReturnRawValues() {
        final byte[] key =
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final PrivacyFilter filter = new PrivacyFilter(
                PrivacyFilter.UserMode.HMAC, PrivacyFilter.IpMode.PREFIX, key);

        assertEquals(filter.userRef("user001"), filter.userRef("user001"));
        assertNotEquals("user001", filter.userRef("user001"));
        assertEquals("192.0.2.0/24", filter.sourceNetwork("192.0.2.25"));
    }
}
