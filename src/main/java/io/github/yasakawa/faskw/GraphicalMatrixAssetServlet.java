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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class GraphicalMatrixAssetServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        final String pathInfo = request.getPathInfo();
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        final Path source;
        final byte[] body;
        final String contentType;
        final int cacheSeconds;
        if ("/graphicalmatrix.css".equals(pathInfo)) {
            if (!config.isCssEnabled()) {
                response.sendError(404);
                return;
            }
            source = config.getCssPath().toAbsolutePath().normalize();
            contentType = "text/css;charset=UTF-8";
            cacheSeconds = config.getCssCacheSeconds();
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                response.sendError(404);
                return;
            }
            body = stylesheet(source, config);
        } else if ("/graphicalmatrix.js".equals(pathInfo)) {
            source = config.getJavascriptPath().toAbsolutePath().normalize();
            contentType = "text/javascript;charset=UTF-8";
            cacheSeconds = config.getJavascriptCacheSeconds();
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                response.sendError(404);
                return;
            }
            body = Files.readAllBytes(source);
        } else {
            response.sendError(404);
            return;
        }

        applyCacheHeaders(response, cacheSeconds);
        response.setContentType(contentType);
        response.setContentLengthLong(body.length);
        response.getOutputStream().write(body);
    }

    @Override
    protected void doHead(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    private static void applyCacheHeaders(final HttpServletResponse response, final int cacheSeconds) {
        if (cacheSeconds <= 0) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
            return;
        }
        response.setHeader("Cache-Control", "private, max-age=" + cacheSeconds);
        response.setDateHeader("Expires", System.currentTimeMillis() + (cacheSeconds * 1000L));
    }

    static byte[] stylesheet(final Path css, final GraphicalMatrixConfig config) throws IOException {
        final String source = Files.readString(css, StandardCharsets.UTF_8);
        final StringBuilder generated = new StringBuilder(source.length() + 256);
        generated.append(source);
        if (!source.endsWith("\n")) {
            generated.append('\n');
        }
        generated.append("\n/* 2FAS-KW managed grid */\n")
            .append(":root {\n")
            .append("  --graphicalmatrix-columns: ")
            .append(config.getColumns())
            .append(";\n")
            .append("}\n");
        if (config.hasResponsiveColumnOverride()) {
            generated.append("\n/* 2FAS-KW managed responsive grid */\n")
                .append("@media (max-width: ")
                .append(config.getMobileBreakpointPx())
                .append("px) {\n")
                .append("  .grid {\n")
                .append("    grid-template-columns: repeat(")
                .append(config.getMobileColumns())
                .append(", minmax(0, 1fr));\n")
                .append("  }\n")
                .append("}\n");
        }
        return generated.toString().getBytes(StandardCharsets.UTF_8);
    }
}
