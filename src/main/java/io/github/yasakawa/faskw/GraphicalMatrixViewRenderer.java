package io.github.yasakawa.faskw;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.owasp.encoder.Encode;

public final class GraphicalMatrixViewRenderer {
    private GraphicalMatrixViewRenderer() {
    }

    public static boolean renderChallenge(final HttpServletRequest request,
            final HttpServletResponse response, final String key, final String challengeId,
            final String csrfToken, final List<String> displayOrder, final GraphicalMatrixConfig config,
            final String selectionInstruction, final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final Path templatePath = config.getTemplatePath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(templatePath) || !Files.isReadable(templatePath)) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("title", "追加認証");
        values.put("heading", "追加認証");
        values.put("lead", Encode.forHtml(selectionInstruction));
        values.put("contextPath", Encode.forHtml(context));
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/verify"));
        values.put("columns", String.valueOf(config.getColumns()));
        values.put("rows", String.valueOf(config.getRows()));
        values.put("choice", String.valueOf(config.getChoiceCount()));
        values.put("orderMode", config.isOrderedSelectionRequired() ? "ordered" : "unordered");
        values.put("cssLink", cssLink(context, config));
        values.put("errorBlock", errorBlock(errorMessage));
        values.put("hiddenInputs", hiddenInputs(key, challengeId, csrfToken));
        values.put("tiles", tiles(context, displayOrder));
        values.put("statusText", "0 / " + config.getChoiceCount() + " 選択済み");
        values.put("scriptBlock", scriptBlock(config));

        return renderTemplate(response, templatePath, values);
    }

    public static boolean renderLocked(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final long remainingMinutes) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final Map<String, String> values = baseValues(request, config);
        values.put("title", "追加認証");
        values.put("heading", "追加認証");
        values.put("errorBlock", errorBlock("制限中です。"));
        values.put("message", "画像認証の失敗回数が上限に達したため、一時的に利用できません。");
        values.put("remainingMinutes", String.valueOf(remainingMinutes));
        return renderTemplate(response, config.getLockedTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderUnavailable(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String title, final String message) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final Map<String, String> values = baseValues(request, config);
        values.put("title", "追加認証");
        values.put("heading", "追加認証");
        values.put("errorBlock", errorBlock(title));
        values.put("message", Encode.forHtml(message));
        return renderTemplate(response, config.getUnavailableTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderTotpRegistration(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String key, final String user, final String seed, final String csrfToken,
            final String qrDataUri, final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "TOTP登録");
        values.put("heading", "TOTP登録");
        values.put("lead", "認証アプリでQRコードを読み取り、表示された6桁コードを入力してください。");
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/verify"));
        values.put("errorBlock", errorBlock(errorMessage));
        values.put("qrDataUri", Encode.forHtml(qrDataUri));
        values.put("user", Encode.forHtml(user));
        values.put("seed", Encode.forHtml(seed));
        values.put("key", Encode.forHtml(key));
        values.put("csrfToken", Encode.forHtml(csrfToken));
        return renderTemplate(response, config.getTotpRegisterTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderSequenceChangeStart(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "GraphicalMatrix変更");
        values.put("heading", "GraphicalMatrix変更");
        values.put("lead", "LDAPのユーザーIDとパスワードで認証してください。続けて現在のGraphicalMatrix確認を行います。");
        values.put("changeUrl", Encode.forHtml(context + "/graphicalmatrix/change"));
        values.put("errorBlock", errorBlock(errorMessage));
        return renderTemplate(response, config.getChangeStartTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderSequenceChangeCurrent(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String user, final String challengeId, final String csrfToken,
            final List<String> displayOrder, final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "GraphicalMatrix変更");
        values.put("heading", "現在のGraphicalMatrix確認");
        values.put("lead", changeCurrentLead(config));
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/change"));
        values.put("columns", String.valueOf(config.getColumns()));
        values.put("choice", String.valueOf(config.getChoiceCount()));
        values.put("errorBlock", errorBlock(errorMessage));
        values.put("hiddenInputs", changeCurrentHiddenInputs(user, challengeId, csrfToken));
        values.put("tiles", tiles(context, displayOrder));
        values.put("statusText", "0 / " + config.getChoiceCount() + " 選択済み");
        values.put("scriptBlock", scriptBlock(config));
        return renderTemplate(response, config.getChangeCurrentTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderSequenceChangeNew(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String user, final String csrfToken, final List<String> displayOrder,
            final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "GraphicalMatrix変更");
        values.put("heading", "新しいGraphicalMatrix登録");
        values.put("lead", changeNewLead(config));
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/change"));
        values.put("columns", String.valueOf(config.getColumns()));
        values.put("choice", String.valueOf(config.getChoiceCount()));
        values.put("errorBlock", errorBlock(errorMessage));
        values.put("hiddenInputs", changeNewHiddenInputs(user, csrfToken));
        values.put("backButton", "<button type=\"submit\" form=\"back-menu-form\">戻る</button>");
        values.put("backMenuForm", backMenuForm(context, user, csrfToken));
        values.put("tiles", tiles(context, displayOrder));
        values.put("statusText", "0 / " + config.getChoiceCount() + " 選択済み");
        values.put("scriptBlock", scriptBlock(config));
        return renderTemplate(response, config.getChangeNewTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderForcedSequenceChange(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String key, final String csrfToken, final List<String> displayOrder,
            final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "GraphicalMatrix変更");
        values.put("heading", "GraphicalMatrix変更");
        values.put("lead", forceChangeLead(config));
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/verify"));
        values.put("columns", String.valueOf(config.getColumns()));
        values.put("choice", String.valueOf(config.getChoiceCount()));
        values.put("errorBlock", errorBlock(errorMessage));
        values.put("hiddenInputs", forcedSequenceHiddenInputs(key, csrfToken));
        values.put("backButton", "");
        values.put("backMenuForm", "");
        values.put("tiles", tiles(context, displayOrder));
        values.put("statusText", "0 / " + config.getChoiceCount() + " 選択済み");
        values.put("scriptBlock", scriptBlock(config));
        return renderTemplate(response, config.getChangeNewTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderSequenceChangeMenu(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String user, final String csrfToken, final boolean methodChangeAllowed,
            final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "MFA設定変更");
        values.put("heading", "MFA設定変更");
        values.put("lead", "変更する項目を選択してください。");
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/change"));
        values.put("user", Encode.forHtml(user));
        values.put("csrfToken", Encode.forHtml(csrfToken));
        values.put("methodChangeAction", methodChangeAction(context, user, csrfToken, methodChangeAllowed));
        values.put("errorBlock", errorBlock(errorMessage));
        return renderTemplate(response, config.getChangeMenuTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderMfaMethodChange(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String user, final String csrfToken, final String currentMethod,
            final String errorMessage) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final String context = request.getContextPath();
        final String normalized = normalizeMethod(currentMethod);
        final Map<String, String> values = baseValues(request, config);
        values.put("title", "MFA方式変更");
        values.put("heading", "MFA方式変更");
        values.put("lead", "新しいGraphicalMatrixを登録しました。次回ログインから利用するMFA方式を選択してください。");
        values.put("formAction", Encode.forHtml(context + "/graphicalmatrix/change"));
        values.put("user", Encode.forHtml(user));
        values.put("csrfToken", Encode.forHtml(csrfToken));
        values.put("errorBlock", errorBlock(errorMessage));
        values.put("graphicalMatrixChecked", "GRAPHICALMATRIX".equals(normalized) ? " checked" : "");
        values.put("totpChecked", "TOTP".equals(normalized) ? " checked" : "");
        values.put("webAuthnChecked", "WEBAUTHN".equals(normalized) ? " checked" : "");
        values.put("backMenuForm", backMenuForm(context, user, csrfToken));
        return renderTemplate(response, config.getChangeMethodTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static boolean renderSequenceChangeComplete(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String user, final String message) throws IOException {
        if (!config.isTemplateEnabled()) {
            return false;
        }

        final Map<String, String> values = baseValues(request, config);
        values.put("title", "GraphicalMatrix変更完了");
        values.put("heading", "GraphicalMatrix変更完了");
        values.put("user", Encode.forHtml(user));
        values.put("message", Encode.forHtml(message));
        return renderTemplate(response, config.getChangeCompleteTemplatePath().toAbsolutePath().normalize(), values);
    }

    public static String cssLink(final String contextPath, final GraphicalMatrixConfig config) {
        if (!config.isCssEnabled()) {
            return "";
        }
        return "<link rel=\"stylesheet\" href=\""
            + Encode.forHtml(contextPath)
            + "/graphicalmatrix/assets/graphicalmatrix.css\">";
    }

    private static String errorBlock(final String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return "";
        }
        return "<div class=\"error\" role=\"alert\">"
            + Encode.forHtml(errorMessage) + "</div>";
    }

    private static String changeCurrentLead(final GraphicalMatrixConfig config) {
        String lead = config.isOrderedSelectionRequired()
            ? "現在登録されている画像を順番に" + config.getChoiceCount() + "つ選択してください。"
            : "現在登録されている画像を" + config.getChoiceCount() + "つ選択してください。選択する順番は問いません。";
        if (config.isDuplicateSelectionsAllowed()) {
            lead += "同じ画像を複数回選択できます。";
        }
        return lead;
    }

    private static String changeNewLead(final GraphicalMatrixConfig config) {
        String lead = "新しく登録する画像を" + config.getChoiceCount()
            + "つ選択してください。選択した順番で登録します。";
        if (config.isDuplicateSelectionsAllowed()) {
            lead += "同じ画像を複数回選択できます。";
        }
        return lead;
    }

    private static String forceChangeLead(final GraphicalMatrixConfig config) {
        String lead = "初回ログインまたは管理者設定により、新しいGraphicalMatrixの登録が必要です。新しく登録する画像を"
            + config.getChoiceCount() + "つ選択してください。";
        if (config.isDuplicateSelectionsAllowed()) {
            lead += "同じ画像を複数回選択できます。";
        }
        return lead;
    }

    private static Map<String, String> baseValues(final HttpServletRequest request,
            final GraphicalMatrixConfig config) {
        final String context = request.getContextPath();
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("contextPath", Encode.forHtml(context));
        values.put("cssLink", cssLink(context, config));
        return values;
    }

    private static boolean renderTemplate(final HttpServletResponse response, final Path templatePath,
            final Map<String, String> values) throws IOException {
        if (!Files.isRegularFile(templatePath) || !Files.isReadable(templatePath)) {
            return false;
        }

        String html = Files.readString(templatePath, StandardCharsets.UTF_8);
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().print(html);
        return true;
    }

    private static String hiddenInputs(final String key, final String challengeId, final String csrfToken) {
        final StringBuilder out = new StringBuilder();
        out.append("<input type=\"hidden\" name=\"key\" value=\"")
            .append(Encode.forHtml(key)).append("\">\n");
        out.append("<input type=\"hidden\" name=\"challengeId\" value=\"")
            .append(Encode.forHtml(challengeId)).append("\">\n");
        out.append("<input type=\"hidden\" name=\"csrfToken\" value=\"")
            .append(Encode.forHtml(csrfToken)).append("\">\n");
        out.append("<input type=\"hidden\" id=\"selected\" name=\"selected\" value=\"\">");
        return out.toString();
    }

    private static String changeCurrentHiddenInputs(final String user, final String challengeId,
            final String csrfToken) {
        final StringBuilder out = new StringBuilder();
        out.append("<input type=\"hidden\" name=\"mode\" value=\"verify-current\">\n");
        out.append("<input type=\"hidden\" name=\"user\" value=\"")
            .append(Encode.forHtml(user)).append("\">\n");
        out.append("<input type=\"hidden\" name=\"challengeId\" value=\"")
            .append(Encode.forHtml(challengeId)).append("\">\n");
        out.append("<input type=\"hidden\" name=\"csrfToken\" value=\"")
            .append(Encode.forHtml(csrfToken)).append("\">\n");
        out.append("<input type=\"hidden\" id=\"selected\" name=\"selected\" value=\"\">");
        return out.toString();
    }

    private static String changeNewHiddenInputs(final String user, final String csrfToken) {
        final StringBuilder out = new StringBuilder();
        out.append("<input type=\"hidden\" name=\"mode\" value=\"save\">\n");
        out.append("<input type=\"hidden\" name=\"user\" value=\"")
            .append(Encode.forHtml(user)).append("\">\n");
        out.append("<input type=\"hidden\" name=\"csrfToken\" value=\"")
            .append(Encode.forHtml(csrfToken)).append("\">\n");
        out.append("<input type=\"hidden\" id=\"selected\" name=\"selected\" value=\"\">");
        return out.toString();
    }

    private static String forcedSequenceHiddenInputs(final String key, final String csrfToken) {
        final StringBuilder out = new StringBuilder();
        out.append("<input type=\"hidden\" name=\"mode\" value=\"force-sequence-save\">\n");
        out.append("<input type=\"hidden\" name=\"key\" value=\"")
            .append(Encode.forHtml(key)).append("\">\n");
        out.append("<input type=\"hidden\" name=\"csrfToken\" value=\"")
            .append(Encode.forHtml(csrfToken)).append("\">\n");
        out.append("<input type=\"hidden\" id=\"selected\" name=\"selected\" value=\"\">");
        return out.toString();
    }

    private static String backMenuForm(final String contextPath, final String user, final String csrfToken) {
        return "<form id=\"back-menu-form\" method=\"post\" action=\""
            + Encode.forHtml(contextPath) + "/graphicalmatrix/change\" autocomplete=\"off\">\n"
            + "  <input type=\"hidden\" name=\"mode\" value=\"back-menu\">\n"
            + "  <input type=\"hidden\" name=\"user\" value=\"" + Encode.forHtml(user) + "\">\n"
            + "  <input type=\"hidden\" name=\"csrfToken\" value=\"" + Encode.forHtml(csrfToken) + "\">\n"
            + "</form>";
    }

    private static String methodChangeAction(final String contextPath, final String user,
            final String csrfToken, final boolean allowed) {
        if (!allowed) {
            return "<div class=\"form-stack\">\n"
                + "  <button type=\"button\" disabled>MFA方式を変更</button>\n"
                + "  <p class=\"muted\">GraphicalMatrixの変更後にMFA方式を変更できます。</p>\n"
                + "</div>";
        }
        return "<form method=\"post\" action=\""
            + Encode.forHtml(contextPath) + "/graphicalmatrix/change\">\n"
            + "  <input type=\"hidden\" name=\"mode\" value=\"choose-method\">\n"
            + "  <input type=\"hidden\" name=\"user\" value=\"" + Encode.forHtml(user) + "\">\n"
            + "  <input type=\"hidden\" name=\"csrfToken\" value=\"" + Encode.forHtml(csrfToken) + "\">\n"
            + "  <button type=\"submit\">MFA方式を変更</button>\n"
            + "</form>";
    }

    private static String tiles(final String contextPath, final List<String> displayOrder) {
        final StringBuilder out = new StringBuilder();
        for (final String id : displayOrder) {
            final String safeId = Encode.forHtml(id);
            out.append("<button class=\"tile\" type=\"button\" data-id=\"")
                .append(safeId).append("\" aria-label=\"").append(safeId).append("\">\n");
            out.append("  <img src=\"").append(Encode.forHtml(contextPath))
                .append("/graphicalmatrix/graphical?id=").append(url(id)).append("\" alt=\"")
                .append(safeId).append("\">\n");
            out.append("  <span class=\"badge\"></span>\n");
            out.append("</button>\n");
        }
        return out.toString();
    }

    private static String scriptBlock(final GraphicalMatrixConfig config) {
        return "<script>\n"
            + "(function () {\n"
            + "  const max = " + config.getChoiceCount() + ";\n"
            + "  const allowDuplicates = " + config.isDuplicateSelectionsAllowed() + ";\n"
            + "  const selected = [];\n"
            + "  const selectedInput = document.getElementById('selected');\n"
            + "  const status = document.getElementById('status');\n"
            + "  const submitButton = document.getElementById('submit-button');\n"
            + "  const form = document.getElementById('graphicalmatrix-form');\n"
            + "  const selectedList = document.getElementById('selected-list');\n"
            + "  const selectedEmpty = document.getElementById('selected-empty');\n"
            + "  const tiles = Array.from(document.querySelectorAll('.tile'));\n"
            + "  function tileFor(id) { return tiles.find(function (tile) { return tile.dataset.id === id; }); }\n"
            + "  function render() {\n"
            + "    tiles.forEach(function (tile) {\n"
            + "      const indexes = selected.map(function (id, index) { return id === tile.dataset.id ? index + 1 : null; }).filter(Boolean);\n"
            + "      const badge = tile.querySelector('.badge');\n"
            + "      if (indexes.length > 0) { tile.classList.add('selected'); badge.textContent = allowDuplicates ? String(indexes.length) : String(indexes[0]); }\n"
            + "      else { tile.classList.remove('selected'); badge.textContent = ''; }\n"
            + "    });\n"
            + "    selectedInput.value = selected.join(',');\n"
            + "    status.textContent = selected.length + ' / ' + max + ' 選択済み';\n"
            + "    submitButton.disabled = selected.length !== max;\n"
            + "    if (selectedList) {\n"
            + "      selectedList.replaceChildren();\n"
            + "      selected.forEach(function (id, index) {\n"
            + "        const tile = tileFor(id);\n"
            + "        const item = document.createElement('li');\n"
            + "        item.className = 'selected-item';\n"
            + "        const order = document.createElement('span');\n"
            + "        order.className = 'selected-order';\n"
            + "        order.textContent = String(index + 1);\n"
            + "        item.appendChild(order);\n"
            + "        if (tile) {\n"
            + "          const source = tile.querySelector('img');\n"
            + "          const graphical = document.createElement('img');\n"
            + "          graphical.src = source.src;\n"
            + "          graphical.alt = '選択した画像';\n"
            + "          item.appendChild(graphical);\n"
            + "        }\n"
            + "        item.addEventListener('click', function () { selected.splice(index, 1); render(); });\n"
            + "        selectedList.appendChild(item);\n"
            + "      });\n"
            + "    }\n"
            + "    if (selectedEmpty) { selectedEmpty.hidden = selected.length > 0; }\n"
            + "  }\n"
            + "  tiles.forEach(function (tile) {\n"
            + "    tile.addEventListener('click', function () {\n"
            + "      const id = tile.dataset.id;\n"
            + "      const idx = selected.indexOf(id);\n"
            + "      if (allowDuplicates) { if (selected.length < max) { selected.push(id); } }\n"
            + "      else if (idx >= 0) { selected.splice(idx, 1); }\n"
            + "      else if (selected.length < max) { selected.push(id); }\n"
            + "      render();\n"
            + "    });\n"
            + "  });\n"
            + "  document.getElementById('reset-button').addEventListener('click', function () { selected.length = 0; render(); });\n"
            + "  if (form && form.dataset.confirm) {\n"
            + "    form.addEventListener('submit', function (event) {\n"
            + "      if (selected.length !== max || !window.confirm(form.dataset.confirm)) { event.preventDefault(); }\n"
            + "    });\n"
            + "  }\n"
            + "  render();\n"
            + "}());\n"
            + "</script>";
    }

    private static String url(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeMethod(final String method) {
        String value = method != null ? method.trim() : "";
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        return value.toUpperCase();
    }
}
