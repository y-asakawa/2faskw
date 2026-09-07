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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

final class GraphicalMatrixSecurityHeadersFilterTest {
    @Test
    void enforcesHtmlHeadersWithoutHsts() {
        final HeaderCapture capture = new HeaderCapture();

        GraphicalMatrixSecurityHeadersFilter.applyHeaders(capture.response(),
            GraphicalMatrixSecurityHeadersFilter.Profile.HTML, "enforce");

        assertEquals(GraphicalMatrixSecurityHeadersFilter.CONTENT_SECURITY_POLICY,
            capture.headers.get("Content-Security-Policy"));
        assertEquals("DENY", capture.headers.get("X-Frame-Options"));
        assertEquals("nosniff", capture.headers.get("X-Content-Type-Options"));
        assertEquals("no-referrer", capture.headers.get("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()",
            capture.headers.get("Permissions-Policy"));
        assertEquals("no-store, no-cache, must-revalidate, max-age=0",
            capture.headers.get("Cache-Control"));
        assertFalse(capture.headers.containsKey("Strict-Transport-Security"));
    }

    @Test
    void reportOnlyModeDoesNotAlsoEnforceCsp() {
        final HeaderCapture capture = new HeaderCapture();

        GraphicalMatrixSecurityHeadersFilter.applyHeaders(capture.response(),
            GraphicalMatrixSecurityHeadersFilter.Profile.HTML, "report-only");

        assertEquals(GraphicalMatrixSecurityHeadersFilter.CONTENT_SECURITY_POLICY,
            capture.headers.get("Content-Security-Policy-Report-Only"));
        assertFalse(capture.headers.containsKey("Content-Security-Policy"));
    }

    @Test
    void apiGetsNoStoreButNoDocumentCsp() {
        final HeaderCapture capture = new HeaderCapture();

        GraphicalMatrixSecurityHeadersFilter.applyHeaders(capture.response(),
            GraphicalMatrixSecurityHeadersFilter.Profile.API, "enforce");

        assertEquals("no-store, no-cache, must-revalidate, max-age=0",
            capture.headers.get("Cache-Control"));
        assertFalse(capture.headers.containsKey("Content-Security-Policy"));
        assertFalse(capture.headers.containsKey("X-Frame-Options"));
    }

    @Test
    void assetAndImageProfilesPreserveEndpointCacheHeaders() {
        for (final GraphicalMatrixSecurityHeadersFilter.Profile profile : new GraphicalMatrixSecurityHeadersFilter.Profile[] {
                GraphicalMatrixSecurityHeadersFilter.Profile.ASSET,
                GraphicalMatrixSecurityHeadersFilter.Profile.IMAGE}) {
            final HeaderCapture capture = new HeaderCapture();
            capture.headers.put("Cache-Control", "private, max-age=60");

            GraphicalMatrixSecurityHeadersFilter.applyHeaders(capture.response(), profile, "enforce");

            assertEquals("private, max-age=60", capture.headers.get("Cache-Control"));
            assertEquals("nosniff", capture.headers.get("X-Content-Type-Options"));
            assertFalse(capture.headers.containsKey("Content-Security-Policy"));
        }
    }

    @Test
    void classifiesOnlyConfiguredPluginBoundaries() {
        assertEquals(GraphicalMatrixSecurityHeadersFilter.Profile.HTML,
            GraphicalMatrixSecurityHeadersFilter.profile(request("/idp", "/idp/graphicalmatrix/start")));
        assertEquals(GraphicalMatrixSecurityHeadersFilter.Profile.ASSET,
            GraphicalMatrixSecurityHeadersFilter.profile(request("/idp", "/idp/graphicalmatrix/assets/graphicalmatrix.js")));
        assertEquals(GraphicalMatrixSecurityHeadersFilter.Profile.IMAGE,
            GraphicalMatrixSecurityHeadersFilter.profile(request("/idp", "/idp/graphicalmatrix/graphical")));
        assertEquals(GraphicalMatrixSecurityHeadersFilter.Profile.API,
            GraphicalMatrixSecurityHeadersFilter.profile(request("/idp", "/idp/graphicalmatrix-admin/api/v1/users")));
        assertEquals(GraphicalMatrixSecurityHeadersFilter.Profile.HTML,
            GraphicalMatrixSecurityHeadersFilter.profile(request("/idp", "/idp/graphicalmatrix/assets/unknown.js")));
    }

    private static HttpServletRequest request(final String contextPath, final String requestUri) {
        return (HttpServletRequest) Proxy.newProxyInstance(
            GraphicalMatrixSecurityHeadersFilterTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getContextPath" -> contextPath;
                case "getRequestURI" -> requestUri;
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class HeaderCapture {
        private final Map<String, String> headers = new LinkedHashMap<>();

        private HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                GraphicalMatrixSecurityHeadersFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("setHeader".equals(method.getName())) {
                        headers.put((String) args[0], (String) args[1]);
                    }
                    return defaultValue(method.getReturnType());
                });
        }
    }
}
