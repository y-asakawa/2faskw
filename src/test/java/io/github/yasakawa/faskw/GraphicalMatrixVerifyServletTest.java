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
import org.opensaml.profile.context.ProfileRequestContext;

final class GraphicalMatrixVerifyServletTest {
    @Test
    void acceptsOnlyTheKeyBoundToTheCurrentFlow() {
        assertTrue(GraphicalMatrixVerifyServlet.matchesFlowKey("flow-a", "flow-a"));
        assertFalse(GraphicalMatrixVerifyServlet.matchesFlowKey("flow-a", "flow-b"));
    }

    @Test
    void rejectsMissingFlowKeys() {
        assertFalse(GraphicalMatrixVerifyServlet.matchesFlowKey(null, "flow-a"));
        assertFalse(GraphicalMatrixVerifyServlet.matchesFlowKey("", ""));
        assertFalse(GraphicalMatrixVerifyServlet.matchesFlowKey("flow-a", null));
    }

    @Test
    void selfServiceProfileCannotUseSpOrIpMfaBypass() {
        final ProfileRequestContext context = new ProfileRequestContext();
        context.setProfileId(GraphicalMatrixSelfServiceAuthentication.PROFILE_ID);

        assertTrue(GraphicalMatrixMfaDecisionStrategy.isSelfServiceProfile(context));
        context.setProfileId("https://sp.example.test/profile");
        assertFalse(GraphicalMatrixMfaDecisionStrategy.isSelfServiceProfile(context));
    }
}
