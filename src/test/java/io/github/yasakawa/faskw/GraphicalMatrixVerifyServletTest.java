package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
