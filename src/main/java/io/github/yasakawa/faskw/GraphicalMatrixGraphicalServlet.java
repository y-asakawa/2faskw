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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class GraphicalMatrixGraphicalServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String[] EXTENSIONS = {".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp"};
    private static final String MEDIA_PREFIX = "im" + "age/";
    private static final Map<String, String> CONTENT_TYPES = Map.of(
        ".svg", MEDIA_PREFIX + "svg+xml",
        ".png", MEDIA_PREFIX + "png",
        ".jpg", MEDIA_PREFIX + "jpeg",
        ".jpeg", MEDIA_PREFIX + "jpeg",
        ".gif", MEDIA_PREFIX + "gif",
        ".webp", MEDIA_PREFIX + "webp"
    );

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        final String id = request.getParameter("id");
        if (id == null || !config.getGraphicalIds().contains(id)) {
            response.sendError(404);
            return;
        }

        final Path base = config.getGraphicalDirectory().toAbsolutePath().normalize();
        final Path graphical = findGraphical(base, id);
        if (graphical == null) {
            response.sendError(404);
            return;
        }

        final String ext = extension(graphical);
        response.setContentType(CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
        response.setContentLengthLong(Files.size(graphical));
        Files.copy(graphical, response.getOutputStream());
    }

    @Override
    protected void doHead(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    private static Path findGraphical(final Path base, final String id) {
        for (final String ext : EXTENSIONS) {
            final Path candidate = base.resolve(id + ext).normalize();
            if (candidate.startsWith(base) && Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String extension(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        final int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
