package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class InitializeGraphicalMatrixSelfServiceTest {
    @Test
    void changeRedirectIsRelativeToIdpWebApplicationContext() {
        assertEquals(
            "contextRelative:/graphicalmatrix/change?mode=idp-self-service",
            InitializeGraphicalMatrixSelfService.CHANGE_REDIRECT_URL);
    }
}
