package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixAdminApiServletJsonBodyTest {
    @Test
    void readsTopLevelStringsWithJsonEscapes() {
        final GraphicalMatrixAdminApiServlet.JsonBody body =
            new GraphicalMatrixAdminApiServlet.JsonBody("{\"mfaMethod\":\"Web\\\"Authn\\n\\u3042\"}");

        assertEquals("Web\"Authn\nあ", body.string("mfaMethod"));
        assertNull(body.string("missing"));
    }

    @Test
    void readsBooleanAndSequenceFormsAcceptedByTheApi() {
        final GraphicalMatrixAdminApiServlet.JsonBody body = new GraphicalMatrixAdminApiServlet.JsonBody(
            "{\"forceSequenceChange\":true,\"forceAsText\":\"on\","
            + "\"initialSequence\":[\" A \",\"B\",\"\"],\"sequence\":\"img01, img02\"}");

        assertEquals(1, body.boolAsInt("forceSequenceChange"));
        assertEquals(1, body.boolAsInt("forceAsText"));
        assertEquals(List.of("A", "B"), body.sequence("initialSequence"));
        assertEquals(List.of("img01", "img02"), body.sequence("sequence"));
    }

    @Test
    void ignoresNestedPropertiesWhenLookingForApiFields() {
        final GraphicalMatrixAdminApiServlet.JsonBody body = new GraphicalMatrixAdminApiServlet.JsonBody(
            "{\"metadata\":{\"sequence\":[\"nested\"]},\"sequence\":[\"img01\",\"img02\"]}");

        assertEquals(List.of("img01", "img02"), body.sequence("sequence"));
    }

    @Test
    void handlesLongEscapedInputWithoutRegularExpressionBacktracking() {
        final String escapedQuotes = "\\\"".repeat(12_000);
        final GraphicalMatrixAdminApiServlet.JsonBody body = new GraphicalMatrixAdminApiServlet.JsonBody(
            "{\"padding\":\"" + escapedQuotes + "\",\"mfaMethod\":\"TOTP\"}");

        assertEquals("TOTP", body.string("mfaMethod"));
    }
}
