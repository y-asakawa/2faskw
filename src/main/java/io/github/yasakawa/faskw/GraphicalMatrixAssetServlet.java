package io.github.yasakawa.faskw;

import java.io.IOException;
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
        if (pathInfo == null || !"/graphicalmatrix.css".equals(pathInfo)) {
            response.sendError(404);
            return;
        }

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        if (!config.isCssEnabled()) {
            response.sendError(404);
            return;
        }

        final Path css = config.getCssPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(css) || !Files.isReadable(css)) {
            response.sendError(404);
            return;
        }

        applyCacheHeaders(response, config.getCssCacheSeconds());
        response.setContentType("text/css;charset=UTF-8");
        response.setContentLengthLong(Files.size(css));
        Files.copy(css, response.getOutputStream());
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
}
