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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixAssetServletTest {
    @TempDir
    Path idpHome;

    @Test
    void appendsManagedDesktopColumnsWhenResponsiveOverrideIsDisabled() throws Exception {
        final Path css = writeConfigAndCss("", ".grid { display: grid; }\n");
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        final byte[] rendered = GraphicalMatrixAssetServlet.stylesheet(css, config);

        final String text = new String(rendered, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(".grid { display: grid; }\n"));
        assertTrue(text.contains("--graphicalmatrix-columns: 5;"));
        assertFalse(text.contains("@media (max-width:"));
    }

    @Test
    void appendsValidatedResponsiveRule() throws Exception {
        final Path css = writeConfigAndCss("""
            graphicalmatrix.mobile.breakpointPx = 430
            graphicalmatrix.mobile.columns = 4
            """, ".grid { display: grid; }");
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        final byte[] rendered = GraphicalMatrixAssetServlet.stylesheet(css, config);
        final String text = new String(rendered, StandardCharsets.UTF_8);

        assertTrue(text.contains("@media (max-width: 430px)"));
        assertTrue(text.contains("grid-template-columns: repeat(4, minmax(0, 1fr));"));
        assertFalse(text.contains("@media (max-width: var("));
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, rendered.length);
    }

    @Test
    void supportsDifferentValidatedBreakpoint() throws Exception {
        final Path css = writeConfigAndCss("""
            graphicalmatrix.mobile.breakpointPx = 640
            graphicalmatrix.mobile.columns = 4
            """, ".grid {}\n");
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());

        final String text = new String(
            GraphicalMatrixAssetServlet.stylesheet(css, config), StandardCharsets.UTF_8);

        assertTrue(text.contains("@media (max-width: 640px)"));
    }

    @Test
    void endpointUsesGeneratedByteLengthAndNoCacheHeaders() throws Exception {
        final Path css = writeConfigAndCss("""
            graphicalmatrix.mobile.breakpointPx = 430
            graphicalmatrix.mobile.columns = 4
            graphicalmatrix.view.css.cacheSeconds = 0
            """, ".grid { display: grid; }\n");
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(idpHome.toString());
        final byte[] expected = GraphicalMatrixAssetServlet.stylesheet(css, config);
        final ResponseCapture capture = new ResponseCapture();
        final String previousIdpHome = System.getProperty("idp.home");
        System.setProperty("idp.home", idpHome.toString());
        try {
            new GraphicalMatrixAssetServlet().doGet(request("/graphicalmatrix.css"), capture.response());
        } finally {
            if (previousIdpHome == null) {
                System.clearProperty("idp.home");
            } else {
                System.setProperty("idp.home", previousIdpHome);
            }
        }

        assertEquals("text/css;charset=UTF-8", capture.contentType);
        assertEquals(expected.length, capture.contentLength);
        assertEquals("no-store, no-cache, must-revalidate, max-age=0",
            capture.headers.get("Cache-Control"));
        assertEquals("no-cache", capture.headers.get("Pragma"));
        assertEquals(0L, capture.dateHeaders.get("Expires"));
        assertEquals(new String(expected, StandardCharsets.UTF_8),
            capture.body.toString(StandardCharsets.UTF_8));
    }

    @Test
    void javascriptEndpointUsesConfiguredFileAndNoCacheHeaders() throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        final Path cssPath = directory.resolve("test.css");
        final Path javascriptPath = directory.resolve("test.js");
        Files.writeString(cssPath, ".grid {}\n");
        Files.writeString(javascriptPath, "\"use strict\";\n");
        Files.writeString(directory.resolve("graphicalmatrix.properties"), """
            graphicalmatrix.view.css = %s
            graphicalmatrix.view.javascript = %s
            graphicalmatrix.view.javascript.cacheSeconds = 0
            """.formatted(cssPath, javascriptPath));

        final ResponseCapture capture = new ResponseCapture();
        final String previousIdpHome = System.getProperty("idp.home");
        System.setProperty("idp.home", idpHome.toString());
        try {
            new GraphicalMatrixAssetServlet().doGet(request("/graphicalmatrix.js"), capture.response());
        } finally {
            if (previousIdpHome == null) {
                System.clearProperty("idp.home");
            } else {
                System.setProperty("idp.home", previousIdpHome);
            }
        }

        assertEquals("text/javascript;charset=UTF-8", capture.contentType);
        assertEquals("no-store, no-cache, must-revalidate, max-age=0",
            capture.headers.get("Cache-Control"));
        assertEquals("\"use strict\";\n", capture.body.toString(StandardCharsets.UTF_8));
    }

    private Path writeConfigAndCss(final String properties, final String css) throws Exception {
        final Path directory = idpHome.resolve("conf/graphicalmatrix");
        Files.createDirectories(directory);
        final Path cssPath = directory.resolve("test.css");
        Files.writeString(cssPath, css);
        Files.writeString(directory.resolve("graphicalmatrix.properties"),
            properties + "graphicalmatrix.view.css = " + cssPath + "\n");
        return cssPath;
    }

    private static HttpServletRequest request(final String pathInfo) {
        return (HttpServletRequest) Proxy.newProxyInstance(
            GraphicalMatrixAssetServletTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> {
                if ("getPathInfo".equals(method.getName())) {
                    return pathInfo;
                }
                return defaultValue(method.getReturnType());
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

    private static final class ResponseCapture {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, Long> dateHeaders = new LinkedHashMap<>();
        private String contentType;
        private long contentLength = -1;

        private HttpServletResponse response() {
            final ServletOutputStream output = new ServletOutputStream() {
                @Override
                public void write(final int value) throws IOException {
                    body.write(value);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(final WriteListener listener) {
                }
            };
            return (HttpServletResponse) Proxy.newProxyInstance(
                GraphicalMatrixAssetServletTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOutputStream":
                            return output;
                        case "setContentType":
                            contentType = (String) args[0];
                            return null;
                        case "setContentLengthLong":
                            contentLength = (Long) args[0];
                            return null;
                        case "setHeader":
                            headers.put((String) args[0], (String) args[1]);
                            return null;
                        case "setDateHeader":
                            dateHeaders.put((String) args[0], (Long) args[1]);
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        }
    }
}
