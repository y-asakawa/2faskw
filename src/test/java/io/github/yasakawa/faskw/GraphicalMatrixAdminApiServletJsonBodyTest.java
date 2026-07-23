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
