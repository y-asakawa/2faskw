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

import java.io.File;
import java.io.FileWriter;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class GraphicalMatrixAuditLogger {
    private final File logFile;

    public GraphicalMatrixAuditLogger(final String idpHome) {
        this.logFile = new File(idpHome, "logs/graphicalmatrix-audit.log");
    }

    public void log(final String event, final String user, final String result,
            final String challengeId, final String detail, final HttpServletRequest request) {
        final HttpSession session = request.getSession(false);
        final String line =
            "ts=" + Instant.now()
            + " event=" + value(event)
            + " user=" + value(user)
            + " result=" + value(result)
            + " ip=" + value(request.getRemoteAddr())
            + " session=" + value(session != null ? session.getId() : null)
            + " challenge=" + value(challengeId)
            + " detail=" + value(detail)
            + System.lineSeparator();

        synchronized (GraphicalMatrixAuditLogger.class) {
            try (FileWriter out = new FileWriter(logFile, true)) {
                out.write(line);
            } catch (Exception ignored) {
                // Authentication must not fail only because audit logging failed.
            }
        }
    }

    private static String value(final Object raw) {
        if (raw == null) {
            return "-";
        }
        final String text = raw.toString();
        if (text.isEmpty()) {
            return "-";
        }
        final StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            switch (ch) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '=':
                    escaped.append("\\=");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case ' ':
                    escaped.append('_');
                    break;
                default:
                    if (Character.isISOControl(ch)) {
                        escaped.append("\\u");
                        escaped.append(String.format("%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }
}
