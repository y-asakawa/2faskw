package io.github.yasakawa.faskw;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import net.shibboleth.idp.authn.ExternalAuthentication;

public final class GraphicalMatrixStartServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());

        try {
            final String key = ExternalAuthentication.startExternalAuthentication(request);
            final String user = GraphicalMatrixPasswordUserResolver.resolve(key, request);
            if (user == null || user.isEmpty()) {
                audit.log("START", user, "FAIL", null, "password_user_missing", request);
                finishEvent(key, "GraphicalMatrixFailed", request, response);
                return;
            }

            final GraphicalMatrixEnrollment enrollment;
            try {
                enrollment = repository.findEnrollment(user);
            } catch (Exception ex) {
                audit.log("START", user, "DB_ERROR", null, ex.getClass().getSimpleName(), request);
                renderUnavailable(request, response,
                    "追加認証情報を確認できません。",
                    "時間をおいて再度ログインするか、管理者に連絡してください。");
                return;
            }

            if (enrollment == null) {
                audit.log("START", user, "ENROLL_REQUIRED", null, "missing_enrollment", request);
                renderUnavailable(request, response,
                    "追加認証が登録されていません。",
                    "このアカウントではログインを完了できません。管理者に連絡してください。");
                return;
            }

            if (!enrollment.isActive()) {
                audit.log("START", user, "ENROLL_REQUIRED", null, "inactive_enrollment", request);
                renderUnavailable(request, response,
                    "追加認証が無効化されています。",
                    "このアカウントではログインを完了できません。管理者に連絡してください。");
                return;
            }

            final GraphicalMatrixMfaSettings settings = repository.findMfaSettings(user);
            if (settings != null && "TOTP".equalsIgnoreCase(normalizeMethod(settings.getMethod()))) {
                final long now = System.currentTimeMillis();
                final String seed = repository.prepareTotpRegistration(user, now);
                if (seed == null || seed.isEmpty()) {
                    audit.log("TOTP_REGISTER_START", user, "ENROLL_REQUIRED", null,
                        "totp_registration_unavailable", request);
                    renderUnavailable(request, response,
                        "TOTP登録を開始できません。",
                        "時間をおいて再度ログインするか、管理者に連絡してください。");
                    return;
                }

                final String csrfToken = GraphicalMatrixSupport.token();
                final HttpSession session = request.getSession();
                session.setAttribute("totpEnroll.key", key);
                session.setAttribute("totpEnroll.user", user);
                session.setAttribute("totpEnroll.csrfToken", csrfToken);
                session.setAttribute("totpEnroll.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
                session.setAttribute("totpEnroll.used", Boolean.FALSE);

                audit.log("TOTP_REGISTER_START", user, "OK", null,
                    "status=" + settings.getTotpStatus(), request);
                renderTotpRegistration(request, response, key, user, seed, csrfToken, null);
                return;
            }

            if (enrollment.getSequence() == null || enrollment.getSequence().trim().isEmpty()) {
                audit.log("START", user, "ENROLL_REQUIRED", null, "empty_sequence", request);
                renderUnavailable(request, response,
                    "追加認証の登録情報が不完全です。",
                    "このアカウントではログインを完了できません。管理者に連絡してください。");
                return;
            }
            if (!repository.sequenceUsable(enrollment.getSequence(), config)) {
                final int sequenceCount = repository.sequenceCount(enrollment.getSequence());
                audit.log("START", user, "ENROLL_REQUIRED", null,
                    "sequence_mismatch,sequence_count=" + sequenceCount
                    + ",choice=" + config.getChoiceCount(), request);
                renderUnavailable(request, response,
                    "追加認証の登録情報が現在の設定と一致していません。",
                    "このアカウントではログインを完了できません。管理者に連絡してください。");
                return;
            }

            final long now = System.currentTimeMillis();
            if (enrollment.getLockedUntil() > now) {
                audit.log("START", user, "LOCKED", null,
                    "locked_until=" + enrollment.getLockedUntil(), request);
                renderLocked(request, response, enrollment.getLockedUntil());
                return;
            }

            final List<String> displayOrder = GraphicalMatrixSupport.shuffledGraphicalIds(config);
            final String challengeId = GraphicalMatrixSupport.token();
            final String csrfToken = GraphicalMatrixSupport.token();
            final HttpSession session = request.getSession();
            session.setAttribute("graphicalmatrix.key", key);
            session.setAttribute("graphicalmatrix.user", user);
            session.setAttribute("graphicalmatrix.challengeId", challengeId);
            session.setAttribute("graphicalmatrix.csrfToken", csrfToken);
            session.setAttribute("graphicalmatrix.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
            session.setAttribute("graphicalmatrix.displayOrder", displayOrder);
            session.setAttribute("graphicalmatrix.config", config);
            session.setAttribute("graphicalmatrix.used", Boolean.FALSE);

            audit.log("CHALLENGE_CREATED", user, "OK", challengeId,
                "graphicals=" + displayOrder.size() + ",columns=" + config.getColumns()
                + ",rows=" + config.getRows() + ",choice=" + config.getChoiceCount(), request);
            render(request, response, key, challengeId, csrfToken, displayOrder, config, null);
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
    }

    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    private static void finishEvent(final String key, final String event,
            final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        request.setAttribute(ExternalAuthentication.AUTHENTICATION_EVENT_KEY, event);
        ExternalAuthentication.finishExternalAuthentication(key, request, response);
    }

    static void render(final HttpServletRequest request, final HttpServletResponse response,
            final String key, final String challengeId, final String csrfToken,
            final List<String> displayOrder, final GraphicalMatrixConfig config,
            final String errorMessage) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        final String context = GraphicalMatrixSupport.html(request.getContextPath());
        final int columns = config.getColumns();
        final int choiceCount = config.getChoiceCount();
        String selectionInstruction = config.isOrderedSelectionRequired()
            ? "登録済みの画像を順番に" + choiceCount + "つ選択してください。"
            : "登録済みの画像を" + choiceCount + "つ選択してください。選択する順番は問いません。";
        if (config.isDuplicateSelectionsAllowed()) {
            selectionInstruction += "同じ画像を複数回選択できます。";
        }
        if (GraphicalMatrixViewRenderer.renderChallenge(request, response, key, challengeId, csrfToken,
                displayOrder, config, selectionInstruction, errorMessage)) {
            return;
        }
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html lang=\"ja\">");
            out.println("<head>");
            out.println("  <meta charset=\"UTF-8\">");
            out.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
            out.println("  <title>追加認証</title>");
            out.println("  " + GraphicalMatrixViewRenderer.cssLink(request.getContextPath(), config));
            out.println("</head>");
            out.println("<body>");
            out.println("<main>");
            out.println("  <h1>追加認証</h1>");
            out.println("  <p class=\"lead\">" + GraphicalMatrixSupport.html(selectionInstruction) + "</p>");
            if (errorMessage != null && !errorMessage.isEmpty()) {
                out.println("  <div class=\"error\" role=\"alert\">" + GraphicalMatrixSupport.html(errorMessage) + "</div>");
            }
            out.println("  <section class=\"panel\" aria-label=\"GraphicalMatrix\">");
            out.println("    <form id=\"graphicalmatrix-form\" method=\"post\" action=\"" + context + "/graphicalmatrix/verify\" autocomplete=\"off\">");
            out.println("      <input type=\"hidden\" name=\"key\" value=\"" + GraphicalMatrixSupport.html(key) + "\">");
            out.println("      <input type=\"hidden\" name=\"challengeId\" value=\"" + GraphicalMatrixSupport.html(challengeId) + "\">");
            out.println("      <input type=\"hidden\" name=\"csrfToken\" value=\"" + GraphicalMatrixSupport.html(csrfToken) + "\">");
            out.println("      <input type=\"hidden\" id=\"selected\" name=\"selected\" value=\"\">");
            out.println("      <div class=\"grid\" style=\"--graphicalmatrix-columns: " + columns + ";\">");
            for (final String id : displayOrder) {
                final String safeId = GraphicalMatrixSupport.html(id);
                out.println("        <button class=\"tile\" type=\"button\" data-id=\"" + safeId + "\" aria-label=\"" + safeId + "\">");
                out.println("          <img src=\"" + context + "/graphicalmatrix/graphical?id=" + safeId + "\" alt=\"" + safeId + "\">");
                out.println("          <span class=\"badge\"></span>");
                out.println("        </button>");
            }
            out.println("      </div>");
            out.println("      <div id=\"status\" class=\"status\">0 / " + choiceCount + " 選択済み</div>");
            out.println("      <div class=\"actions\">");
            out.println("        <button class=\"primary\" id=\"submit-button\" type=\"submit\" disabled>送信</button>");
            out.println("        <button id=\"reset-button\" type=\"button\">リセット</button>");
            out.println("      </div>");
            out.println("    </form>");
            out.println("  </section>");
            out.println("</main>");
            out.println("<script>");
            out.println("(function () {");
            out.println("  const max = " + choiceCount + ";");
            out.println("  const allowDuplicates = " + config.isDuplicateSelectionsAllowed() + ";");
            out.println("  const selected = [];");
            out.println("  const selectedInput = document.getElementById('selected');");
            out.println("  const status = document.getElementById('status');");
            out.println("  const submitButton = document.getElementById('submit-button');");
            out.println("  const tiles = Array.from(document.querySelectorAll('.tile'));");
            out.println("  function render() {");
            out.println("    tiles.forEach(function (tile) {");
            out.println("      const indexes = selected.map(function (id, index) { return id === tile.dataset.id ? index + 1 : null; }).filter(Boolean);");
            out.println("      const badge = tile.querySelector('.badge');");
            out.println("      if (indexes.length > 0) { tile.classList.add('selected'); badge.textContent = allowDuplicates ? String(indexes.length) : String(indexes[0]); }");
            out.println("      else { tile.classList.remove('selected'); badge.textContent = ''; }");
            out.println("    });");
            out.println("    selectedInput.value = selected.join(',');");
            out.println("    status.textContent = selected.length + ' / ' + max + ' 選択済み';");
            out.println("    submitButton.disabled = selected.length !== max;");
            out.println("  }");
            out.println("  tiles.forEach(function (tile) {");
            out.println("    tile.addEventListener('click', function () {");
            out.println("      const id = tile.dataset.id;");
            out.println("      const idx = selected.indexOf(id);");
            out.println("      if (allowDuplicates) { if (selected.length < max) { selected.push(id); } }");
            out.println("      else if (idx >= 0) { selected.splice(idx, 1); }");
            out.println("      else if (selected.length < max) { selected.push(id); }");
            out.println("      render();");
            out.println("    });");
            out.println("  });");
            out.println("  document.getElementById('reset-button').addEventListener('click', function () { selected.length = 0; render(); });");
            out.println("  render();");
            out.println("}());");
            out.println("</script>");
            out.println("</body>");
            out.println("</html>");
        }
    }

    static void renderLocked(final HttpServletRequest request, final HttpServletResponse response,
            final long lockedUntil) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        final long remainingSeconds = Math.max(0L, (lockedUntil - System.currentTimeMillis() + 999L) / 1000L);
        final long remainingMinutes = Math.max(1L, (remainingSeconds + 59L) / 60L);
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        if (GraphicalMatrixViewRenderer.renderLocked(request, response, config, remainingMinutes)) {
            return;
        }
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html lang=\"ja\">");
            out.println("<head>");
            out.println("  <meta charset=\"UTF-8\">");
            out.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
            out.println("  <title>追加認証</title>");
            out.println("  <style>");
            out.println("    :root { color-scheme: light; }");
            out.println("    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif; background: #f6f7f9; color: #172033; }");
            out.println("    main { max-width: 680px; margin: 0 auto; padding: 32px 18px; }");
            out.println("    h1 { margin: 0 0 10px; font-size: 26px; line-height: 1.25; font-weight: 700; letter-spacing: 0; }");
            out.println("    .panel { background: #fff; border: 1px solid #d9dee7; border-radius: 8px; padding: 20px; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06); }");
            out.println("    .error { margin: 0 0 14px; border: 1px solid #f4b4b4; background: #fff1f1; color: #9f1d1d; border-radius: 7px; padding: 10px 12px; font-weight: 700; line-height: 1.5; }");
            out.println("    p { margin: 0 0 10px; color: #4b5563; line-height: 1.6; }");
            out.println("  </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("<main>");
            out.println("  <h1>追加認証</h1>");
            out.println("  <section class=\"panel\" aria-label=\"GraphicalMatrix locked\">");
            out.println("    <div class=\"error\" role=\"alert\">制限中です。</div>");
            out.println("    <p>画像認証の失敗回数が上限に達したため、一時的に利用できません。</p>");
            out.println("    <p>約" + remainingMinutes + "分後に、サービス画面からログインをやり直してください。</p>");
            out.println("  </section>");
            out.println("</main>");
            out.println("</body>");
            out.println("</html>");
        }
    }

    static void renderUnavailable(final HttpServletRequest request, final HttpServletResponse response,
            final String title, final String message) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        if (GraphicalMatrixViewRenderer.renderUnavailable(request, response, config, title, message)) {
            return;
        }
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html lang=\"ja\">");
            out.println("<head>");
            out.println("  <meta charset=\"UTF-8\">");
            out.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
            out.println("  <title>追加認証</title>");
            out.println("  <style>");
            out.println("    :root { color-scheme: light; }");
            out.println("    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif; background: #f6f7f9; color: #172033; }");
            out.println("    main { max-width: 680px; margin: 0 auto; padding: 32px 18px; }");
            out.println("    h1 { margin: 0 0 10px; font-size: 26px; line-height: 1.25; font-weight: 700; letter-spacing: 0; }");
            out.println("    .panel { background: #fff; border: 1px solid #d9dee7; border-radius: 8px; padding: 20px; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06); }");
            out.println("    .error { margin: 0 0 14px; border: 1px solid #f4b4b4; background: #fff1f1; color: #9f1d1d; border-radius: 7px; padding: 10px 12px; font-weight: 700; line-height: 1.5; }");
            out.println("    p { margin: 0 0 10px; color: #4b5563; line-height: 1.6; }");
            out.println("  </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("<main>");
            out.println("  <h1>追加認証</h1>");
            out.println("  <section class=\"panel\" aria-label=\"GraphicalMatrix unavailable\">");
            out.println("    <div class=\"error\" role=\"alert\">" + GraphicalMatrixSupport.html(title) + "</div>");
            out.println("    <p>" + GraphicalMatrixSupport.html(message) + "</p>");
            out.println("  </section>");
            out.println("</main>");
            out.println("</body>");
            out.println("</html>");
        }
    }

    static void renderTotpRegistration(final HttpServletRequest request, final HttpServletResponse response,
            final String key, final String user, final String seed, final String csrfToken,
            final String errorMessage) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        final String context = GraphicalMatrixSupport.html(request.getContextPath());
        final String issuer = "2FAS-KW";
        final String otpauth = GraphicalMatrixTotpSupport.otpauthUrl(issuer, user, seed);
        final String qrDataUri;
        try {
            final String svg = GraphicalMatrixTotpSupport.qrSvg(otpauth, 5, 4);
            qrDataUri = "data:graphical/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IOException("Unable to generate TOTP QR code", ex);
        }
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        if (GraphicalMatrixViewRenderer.renderTotpRegistration(request, response, config, key, user, seed,
                csrfToken, qrDataUri, errorMessage)) {
            return;
        }

        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html lang=\"ja\">");
            out.println("<head>");
            out.println("  <meta charset=\"UTF-8\">");
            out.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
            out.println("  <title>TOTP登録</title>");
            out.println("  <style>");
            out.println("    :root { color-scheme: light; }");
            out.println("    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif; background: #f6f7f9; color: #172033; }");
            out.println("    main { max-width: 720px; margin: 0 auto; padding: 28px 18px 36px; }");
            out.println("    h1 { margin: 0 0 8px; font-size: 26px; line-height: 1.25; font-weight: 700; letter-spacing: 0; }");
            out.println("    .lead { margin: 0 0 18px; color: #4b5563; line-height: 1.6; }");
            out.println("    .panel { background: #fff; border: 1px solid #d9dee7; border-radius: 8px; padding: 20px; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06); }");
            out.println("    .layout { display: grid; grid-template-columns: 220px 1fr; gap: 20px; align-items: start; }");
            out.println("    .qr { width: 220px; height: 220px; border: 1px solid #d9dee7; border-radius: 8px; }");
            out.println("    .error { margin: 0 0 14px; border: 1px solid #f4b4b4; background: #fff1f1; color: #9f1d1d; border-radius: 7px; padding: 10px 12px; font-weight: 700; line-height: 1.5; }");
            out.println("    label { display: block; margin: 14px 0 6px; font-weight: 700; }");
            out.println("    input[type=text] { width: 100%; max-width: 220px; min-height: 42px; box-sizing: border-box; border: 1px solid #b8c0cc; border-radius: 7px; padding: 0 12px; font-size: 18px; letter-spacing: 0; }");
            out.println("    code { overflow-wrap: anywhere; background: #f1f5f9; border: 1px solid #d9dee7; border-radius: 6px; padding: 2px 5px; }");
            out.println("    .actions { margin-top: 14px; display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }");
            out.println("    button { min-height: 42px; border-radius: 7px; border: 1px solid #0f766e; background: #0f766e; color: #fff; font-weight: 700; padding: 0 16px; cursor: pointer; }");
            out.println("    p { margin: 0 0 10px; color: #4b5563; line-height: 1.6; }");
            out.println("    @media (max-width: 640px) { .layout { grid-template-columns: 1fr; } .qr { width: 200px; height: 200px; } h1 { font-size: 22px; } }");
            out.println("  </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("<main>");
            out.println("  <h1>TOTP登録</h1>");
            out.println("  <p class=\"lead\">認証アプリでQRコードを読み取り、表示された6桁コードを入力してください。</p>");
            if (errorMessage != null && !errorMessage.isEmpty()) {
                out.println("  <div class=\"error\" role=\"alert\">" + GraphicalMatrixSupport.html(errorMessage) + "</div>");
            }
            out.println("  <section class=\"panel\" aria-label=\"TOTP registration\">");
            out.println("    <div class=\"layout\">");
            out.println("      <img class=\"qr\" src=\"" + qrDataUri + "\" alt=\"TOTP登録QRコード\">");
            out.println("      <div>");
            out.println("        <p>アカウント: <strong>" + GraphicalMatrixSupport.html(user) + "</strong></p>");
            out.println("        <p>QRコードを読めない場合は、認証アプリに次のキーを手入力してください。</p>");
            out.println("        <p><code>" + GraphicalMatrixSupport.html(seed) + "</code></p>");
            out.println("        <form method=\"post\" action=\"" + context + "/graphicalmatrix/verify\" autocomplete=\"off\">");
            out.println("          <input type=\"hidden\" name=\"mode\" value=\"totp-register\">");
            out.println("          <input type=\"hidden\" name=\"key\" value=\"" + GraphicalMatrixSupport.html(key) + "\">");
            out.println("          <input type=\"hidden\" name=\"csrfToken\" value=\"" + GraphicalMatrixSupport.html(csrfToken) + "\">");
            out.println("          <label for=\"tokencode\">6桁コード</label>");
            out.println("          <input id=\"tokencode\" name=\"tokencode\" type=\"text\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" maxlength=\"6\" required autofocus>");
            out.println("          <div class=\"actions\"><button type=\"submit\">登録して続行</button></div>");
            out.println("        </form>");
            out.println("      </div>");
            out.println("    </div>");
            out.println("  </section>");
            out.println("</main>");
            out.println("</body>");
            out.println("</html>");
        }
    }

    private static String normalizeMethod(final String method) {
        String value = method != null ? method.trim() : "";
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        return value.toUpperCase();
    }
}
