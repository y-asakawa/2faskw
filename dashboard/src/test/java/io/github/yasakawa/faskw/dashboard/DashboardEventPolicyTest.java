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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DashboardEventPolicyTest {

    @Test
    void classifiesOnlyExplicitOperationalEventsAndExactAdminPrefix() {
        assertEquals(
                DashboardEventPolicy.EventClass.AUTHENTICATION,
                DashboardEventPolicy.classify("VERIFY"));
        assertEquals(
                DashboardEventPolicy.EventClass.SELF_SERVICE,
                DashboardEventPolicy.classify("CHANGE_SAVE"));
        assertEquals(
                DashboardEventPolicy.EventClass.ADMIN_API,
                DashboardEventPolicy.classify("API_USER_UPDATED"));
        assertEquals(
                DashboardEventPolicy.EventClass.RESTRICTED_OTHER,
                DashboardEventPolicy.classify("APIX_USER_UPDATED"));
        assertEquals(
                DashboardEventPolicy.EventClass.RESTRICTED_OTHER,
                DashboardEventPolicy.classify("UNRECOGNIZED"));
        assertEquals(
                DashboardEventPolicy.EventClass.RESTRICTED_OTHER,
                DashboardEventPolicy.classify(null));
    }

    @Test
    void derivesScopeOnlyFromTheAuthenticatedPrincipalRole() {
        for (DashboardAuthorizer.Role role : new DashboardAuthorizer.Role[] {
                DashboardAuthorizer.Role.DASHBOARD_VIEWER,
                DashboardAuthorizer.Role.DASHBOARD_OPERATOR}) {
            final DashboardEventScope scope = scope(role);
            assertEquals("OPERATIONAL", scope.externalName());
            assertTrue(scope.allows("VERIFY"));
            assertTrue(scope.allows("CHANGE_SAVE"));
            assertFalse(scope.allows("API_USER_UPDATED"));
            assertFalse(scope.allows("UNRECOGNIZED"));
            assertFalse(scope.allowsLockedUsers());
        }

        for (DashboardAuthorizer.Role role : new DashboardAuthorizer.Role[] {
                DashboardAuthorizer.Role.DASHBOARD_AUDITOR,
                DashboardAuthorizer.Role.DASHBOARD_ADMIN}) {
            final DashboardEventScope scope = scope(role);
            assertEquals("ALL", scope.externalName());
            assertTrue(scope.allows("VERIFY"));
            assertTrue(scope.allows("API_USER_UPDATED"));
            assertTrue(scope.allows("UNRECOGNIZED"));
            assertTrue(scope.allowsLockedUsers());
        }
    }

    @Test
    void rejectsMissingPrincipalContext() {
        assertThrows(NullPointerException.class, () -> DashboardEventPolicy.scopeFor(null));
    }

    private static DashboardEventScope scope(final DashboardAuthorizer.Role role) {
        return DashboardEventPolicy.scopeFor(new DashboardAuthorizer.Principal(
                "test-user", role, "user:test-user"));
    }
}
