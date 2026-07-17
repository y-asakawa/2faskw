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
