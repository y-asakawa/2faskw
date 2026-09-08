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
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class GraphicalMatrixSecurityHeadersFilter implements Filter {
    static final String CONTENT_SECURITY_POLICY =
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        + "connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; "
        + "base-uri 'none'; object-src 'none'";
    private static final String NO_STORE = "no-store, no-cache, must-revalidate, max-age=0";
    private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";
    private static final AtomicBoolean DISABLED_WARNING_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean REPORT_ONLY_WARNING_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CONFIG_WARNING_LOGGED = new AtomicBoolean();

    enum Profile {
        HTML,
        API,
        ASSET,
        IMAGE
    }

    @Override
    public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse,
            final FilterChain chain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        boolean enabled = true;
        String cspMode = "enforce";
        try {
            final GraphicalMatrixConfig config =
                GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
            enabled = config.isSecurityHeadersEnabled();
            cspMode = config.getSecurityHeadersCspMode();
        } catch (Exception ex) {
            logOnce(request, CONFIG_WARNING_LOGGED,
                "2FAS-KW security header configuration could not be loaded; using enforce defaults: "
                + ex.getClass().getSimpleName());
        }

        if (enabled) {
            applyHeaders(response, profile(request), cspMode);
            if ("report-only".equals(cspMode)) {
                logOnce(request, REPORT_ONLY_WARNING_LOGGED,
                    "2FAS-KW Content-Security-Policy is running in report-only mode");
            }
        } else {
            logOnce(request, DISABLED_WARNING_LOGGED,
                "2FAS-KW security headers are disabled");
        }
        chain.doFilter(servletRequest, servletResponse);
    }

    static Profile profile(final HttpServletRequest request) {
        final String requestUri = request.getRequestURI() != null ? request.getRequestURI() : "";
        final String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        final String path = !contextPath.isEmpty() && requestUri.startsWith(contextPath)
            ? requestUri.substring(contextPath.length()) : requestUri;
        if (path.equals("/graphicalmatrix-admin/api/v1")
                || path.startsWith("/graphicalmatrix-admin/api/v1/")) {
            return Profile.API;
        }
        if (path.equals("/graphicalmatrix/assets/graphicalmatrix.css")
                || path.equals("/graphicalmatrix/assets/graphicalmatrix.js")) {
            return Profile.ASSET;
        }
        if (path.equals("/graphicalmatrix/graphical")) {
            return Profile.IMAGE;
        }
        return Profile.HTML;
    }

    static void applyHeaders(final HttpServletResponse response, final Profile profile,
            final String cspMode) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");

        if (profile == Profile.ASSET || profile == Profile.IMAGE) {
            return;
        }

        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Cache-Control", NO_STORE);
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        if (profile == Profile.HTML) {
            final String header = "report-only".equals(cspMode)
                ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy";
            response.setHeader(header, CONTENT_SECURITY_POLICY);
            response.setHeader("X-Frame-Options", "DENY");
        }
    }

    private static void logOnce(final HttpServletRequest request, final AtomicBoolean state,
            final String message) {
        if (state.compareAndSet(false, true)) {
            request.getServletContext().log(message);
        }
    }
}
