package io.github.yasakawa.faskw;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import net.shibboleth.idp.authn.ExternalAuthentication;

public final class GraphicalMatrixVerifyServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 15L * 60L * 1000L;

    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("VERIFY", null, "BAD_REQUEST", null, "session_missing", request);
            response.sendError(400, "GraphicalMatrix session is missing.");
            return;
        }

        final String sessionKey = (String) session.getAttribute("graphicalmatrix.key");
        final String submittedKey = request.getParameter("key");
        final String key = (sessionKey != null) ? sessionKey : submittedKey;
        if (key == null || key.isEmpty()) {
            audit.log("VERIFY", null, "BAD_REQUEST", null, "external_key_missing", request);
            response.sendError(400, "External authentication key is missing.");
            return;
        }

        if ("totp-register".equals(request.getParameter("mode"))) {
            handleTotpRegistration(request, response, session, key, audit, repository);
            return;
        }
        if ("totp-register-cancel".equals(request.getParameter("mode"))) {
            handleTotpRegistrationCancel(request, response, session, key, audit, repository);
            return;
        }
        if ("force-sequence-save".equals(request.getParameter("mode"))) {
            handleForcedSequenceSave(request, response, session, key, audit, repository);
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrix.user");
        final String challengeId = (String) session.getAttribute("graphicalmatrix.challengeId");
        final String csrfToken = (String) session.getAttribute("graphicalmatrix.csrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrix.expiresAt");
        final Boolean used = (Boolean) session.getAttribute("graphicalmatrix.used");
        final Object displayOrderObject = session.getAttribute("graphicalmatrix.displayOrder");
        final Object configObject = session.getAttribute("graphicalmatrix.config");

        @SuppressWarnings("unchecked")
        final List<String> displayOrder = (displayOrderObject instanceof List)
            ? (List<String>) displayOrderObject : new ArrayList<>();
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());

        final long now = System.currentTimeMillis();
        GraphicalMatrixVerifyResult result = GraphicalMatrixVerifyResult.failed("invalid_or_expired_challenge");
        if (user != null
                && String.valueOf(sessionKey).equals(String.valueOf(submittedKey))
                && String.valueOf(challengeId).equals(String.valueOf(request.getParameter("challengeId")))
                && String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
                && expiresAt != null
                && expiresAt.longValue() >= now
                && !Boolean.TRUE.equals(used)) {
            session.setAttribute("graphicalmatrix.used", Boolean.TRUE);
            result = repository.verify(
                user,
                GraphicalMatrixSupport.csv(request.getParameter("selected")),
                displayOrder,
                now,
                MAX_FAILURES,
                LOCK_MILLIS,
                config.isOrderedSelectionRequired(),
                config.isDuplicateSelectionsAllowed()
            );
        }

        audit.log("VERIFY", user, result.getAuditResult(), challengeId, result.getAuditDetail(), request);

        try {
            if (result.isSuccess()) {
                if (config.isForceSequenceChangeEnabled()
                        && repository.isForceSequenceChangeRequired(user)) {
                    final String forceCsrfToken = GraphicalMatrixSupport.token();
                    final List<String> forceDisplayOrder = orderedGraphicalIds(config);
                    session.setAttribute("forceSequence.key", key);
                    session.setAttribute("forceSequence.user", user);
                    session.setAttribute("forceSequence.csrfToken", forceCsrfToken);
                    session.setAttribute("forceSequence.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
                    session.setAttribute("forceSequence.displayOrder", forceDisplayOrder);
                    session.setAttribute("forceSequence.config", config);
                    audit.log("FORCE_SEQUENCE_CHANGE_START", user, "OK", challengeId,
                        "graphicals=" + forceDisplayOrder.size() + ",choice=" + config.getChoiceCount(), request);
                    clearChallenge(session);
                    renderForcedSequenceChange(request, response, config, key, forceCsrfToken,
                        forceDisplayOrder, null);
                    return;
                }
                request.setAttribute(ExternalAuthentication.PRINCIPAL_NAME_KEY, user);
                clearChallenge(session);
                ExternalAuthentication.finishExternalAuthentication(key, request, response);
                return;
            }

            if (isRetryableFailure(result)) {
                final List<String> retryDisplayOrder = GraphicalMatrixSupport.shuffledGraphicalIds(config);
                final String retryChallengeId = GraphicalMatrixSupport.token();
                final String retryCsrfToken = GraphicalMatrixSupport.token();
                session.setAttribute("graphicalmatrix.key", key);
                session.setAttribute("graphicalmatrix.user", user);
                session.setAttribute("graphicalmatrix.challengeId", retryChallengeId);
                session.setAttribute("graphicalmatrix.csrfToken", retryCsrfToken);
                session.setAttribute("graphicalmatrix.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
                session.setAttribute("graphicalmatrix.displayOrder", retryDisplayOrder);
                session.setAttribute("graphicalmatrix.config", config);
                session.setAttribute("graphicalmatrix.used", Boolean.FALSE);
                audit.log("CHALLENGE_CREATED", user, "OK", retryChallengeId,
                    "graphicals=" + retryDisplayOrder.size() + ",columns=" + config.getColumns()
                    + ",rows=" + config.getRows() + ",choice=" + config.getChoiceCount()
                    + ",retry_after_fail", request);
                GraphicalMatrixStartServlet.render(request, response, key, retryChallengeId,
                    retryCsrfToken, retryDisplayOrder, config, retryMessage(result));
                return;
            }

            if ("LOCKED".equals(result.getAuditResult())) {
                clearChallenge(session);
                GraphicalMatrixStartServlet.renderLocked(request, response, now + LOCK_MILLIS);
                return;
            }

            clearChallenge(session);
            request.setAttribute(ExternalAuthentication.AUTHENTICATION_EVENT_KEY, result.getEvent());
            ExternalAuthentication.finishExternalAuthentication(key, request, response);
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        response.sendError(405, "Use POST for GraphicalMatrix verification.");
    }

    private static void clearChallenge(final HttpSession session) {
        session.removeAttribute("graphicalmatrix.key");
        session.removeAttribute("graphicalmatrix.user");
        session.removeAttribute("graphicalmatrix.challengeId");
        session.removeAttribute("graphicalmatrix.csrfToken");
        session.removeAttribute("graphicalmatrix.expiresAt");
        session.removeAttribute("graphicalmatrix.displayOrder");
        session.removeAttribute("graphicalmatrix.config");
        session.removeAttribute("graphicalmatrix.used");
    }

    private static void handleTotpRegistration(final HttpServletRequest request,
            final HttpServletResponse response, final HttpSession session, final String key,
            final GraphicalMatrixAuditLogger audit, final GraphicalMatrixRepository repository)
            throws ServletException, IOException {
        final String user = (String) session.getAttribute("totpEnroll.user");
        final String sessionKey = (String) session.getAttribute("totpEnroll.key");
        final String csrfToken = (String) session.getAttribute("totpEnroll.csrfToken");
        final Long expiresAt = (Long) session.getAttribute("totpEnroll.expiresAt");
        final Boolean used = (Boolean) session.getAttribute("totpEnroll.used");
        final long now = System.currentTimeMillis();

        if (user == null
                || !String.valueOf(sessionKey).equals(String.valueOf(request.getParameter("key")))
                || !String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
                || expiresAt == null
                || expiresAt.longValue() < now
                || Boolean.TRUE.equals(used)) {
            audit.log("TOTP_REGISTER_VERIFY", user, "BAD_REQUEST", null,
                "invalid_or_expired_registration", request);
            clearTotpRegistration(session);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "TOTP登録を確認できません。",
                "登録画面の有効期限が切れています。サービス画面からログインをやり直してください。");
            return;
        }

        final GraphicalMatrixVerifyResult result =
            repository.verifyAndActivateTotp(user, request.getParameter("tokencode"), now);
        audit.log("TOTP_REGISTER_VERIFY", user, result.getAuditResult(), null,
            result.getAuditDetail(), request);

        try {
            if (result.isSuccess()) {
                session.setAttribute("totpEnroll.used", Boolean.TRUE);
                request.setAttribute(ExternalAuthentication.PRINCIPAL_NAME_KEY, user);
                clearTotpRegistration(session);
                ExternalAuthentication.finishExternalAuthentication(key, request, response);
                return;
            }

            if ("FAIL".equals(result.getAuditResult())) {
                final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
                final String seed = repository.prepareTotpRegistration(user, now);
                final String retryCsrfToken = GraphicalMatrixSupport.token();
                session.setAttribute("totpEnroll.key", key);
                session.setAttribute("totpEnroll.user", user);
                session.setAttribute("totpEnroll.csrfToken", retryCsrfToken);
                session.setAttribute("totpEnroll.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
                session.setAttribute("totpEnroll.used", Boolean.FALSE);
                GraphicalMatrixStartServlet.renderTotpRegistration(request, response, key, user,
                    seed, retryCsrfToken, "コードが正しくありません。認証アプリの6桁コードを確認してください。");
                return;
            }

            clearTotpRegistration(session);
            request.setAttribute(ExternalAuthentication.AUTHENTICATION_EVENT_KEY, result.getEvent());
            ExternalAuthentication.finishExternalAuthentication(key, request, response);
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
    }

    private static void handleTotpRegistrationCancel(final HttpServletRequest request,
            final HttpServletResponse response, final HttpSession session, final String key,
            final GraphicalMatrixAuditLogger audit, final GraphicalMatrixRepository repository)
            throws ServletException, IOException {
        final String user = (String) session.getAttribute("totpEnroll.user");
        final String sessionKey = (String) session.getAttribute("totpEnroll.key");
        final String csrfToken = (String) session.getAttribute("totpEnroll.csrfToken");
        final Long expiresAt = (Long) session.getAttribute("totpEnroll.expiresAt");
        final Boolean used = (Boolean) session.getAttribute("totpEnroll.used");
        final long now = System.currentTimeMillis();

        if (user == null
                || !String.valueOf(sessionKey).equals(String.valueOf(request.getParameter("key")))
                || !String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
                || expiresAt == null
                || expiresAt.longValue() < now
                || Boolean.TRUE.equals(used)) {
            audit.log("TOTP_REGISTER_CANCEL", user, "BAD_REQUEST", null,
                "invalid_or_expired_registration", request);
            clearTotpRegistration(session);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "TOTP登録を取り消せません。",
                "登録画面の有効期限が切れています。サービス画面からログインをやり直してください。");
            return;
        }

        try {
            if (!repository.updateMfaMethod(user, "GraphicalMatrix", now)) {
                audit.log("TOTP_REGISTER_CANCEL", user, "ENROLL_REQUIRED", null, "missing_enrollment", request);
                clearTotpRegistration(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixに戻せません。",
                    "このアカウントの登録情報を確認できません。管理者に連絡してください。");
                return;
            }

            final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
            final GraphicalMatrixEnrollment enrollment = repository.findEnrollment(user);
            if (enrollment == null || !enrollment.isActive()) {
                audit.log("TOTP_REGISTER_CANCEL", user, "ENROLL_REQUIRED", null,
                    "missing_or_inactive", request);
                clearTotpRegistration(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixに戻せません。",
                    "このアカウントのGraphicalMatrix登録情報を確認できません。管理者に連絡してください。");
                return;
            }

            if (!repository.sequenceUsable(enrollment.getSequence(), config)) {
                final int sequenceCount = repository.sequenceCount(enrollment.getSequence());
                audit.log("TOTP_REGISTER_CANCEL", user, "ENROLL_REQUIRED", null,
                    "sequence_mismatch,sequence_count=" + sequenceCount, request);
                clearTotpRegistration(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixに戻せません。",
                    "登録済みのGraphicalMatrixが現在の設定と一致していません。管理者に連絡してください。");
                return;
            }

            if (enrollment.getLockedUntil() > now) {
                audit.log("TOTP_REGISTER_CANCEL", user, "LOCKED", null,
                    "locked_until=" + enrollment.getLockedUntil(), request);
                clearTotpRegistration(session);
                GraphicalMatrixStartServlet.renderLocked(request, response, enrollment.getLockedUntil());
                return;
            }

            final List<String> displayOrder = GraphicalMatrixSupport.shuffledGraphicalIds(config);
            final String challengeId = GraphicalMatrixSupport.token();
            final String csrf = GraphicalMatrixSupport.token();
            clearTotpRegistration(session);
            session.setAttribute("graphicalmatrix.key", key);
            session.setAttribute("graphicalmatrix.user", user);
            session.setAttribute("graphicalmatrix.challengeId", challengeId);
            session.setAttribute("graphicalmatrix.csrfToken", csrf);
            session.setAttribute("graphicalmatrix.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
            session.setAttribute("graphicalmatrix.displayOrder", displayOrder);
            session.setAttribute("graphicalmatrix.config", config);
            session.setAttribute("graphicalmatrix.used", Boolean.FALSE);

            audit.log("TOTP_REGISTER_CANCEL", user, "OK", challengeId,
                "mfa_method=GraphicalMatrix,graphicals=" + displayOrder.size(), request);
            GraphicalMatrixStartServlet.render(request, response, key, challengeId, csrf,
                displayOrder, config, "TOTP登録を取り消し、GraphicalMatrixに戻しました。");
        } catch (Exception ex) {
            audit.log("TOTP_REGISTER_CANCEL", user, "DB_ERROR", null,
                ex.getClass().getSimpleName(), request);
            throw new ServletException(ex);
        }
    }

    private static void clearTotpRegistration(final HttpSession session) {
        session.removeAttribute("totpEnroll.key");
        session.removeAttribute("totpEnroll.user");
        session.removeAttribute("totpEnroll.csrfToken");
        session.removeAttribute("totpEnroll.expiresAt");
        session.removeAttribute("totpEnroll.used");
    }

    private static void handleForcedSequenceSave(final HttpServletRequest request,
            final HttpServletResponse response, final HttpSession session, final String key,
            final GraphicalMatrixAuditLogger audit, final GraphicalMatrixRepository repository)
            throws ServletException, IOException {
        final String user = (String) session.getAttribute("forceSequence.user");
        final String sessionKey = (String) session.getAttribute("forceSequence.key");
        final String csrfToken = (String) session.getAttribute("forceSequence.csrfToken");
        final Long expiresAt = (Long) session.getAttribute("forceSequence.expiresAt");
        final Object displayOrderObject = session.getAttribute("forceSequence.displayOrder");
        final Object configObject = session.getAttribute("forceSequence.config");

        @SuppressWarnings("unchecked")
        final List<String> displayOrder = (displayOrderObject instanceof List)
            ? (List<String>) displayOrderObject : new ArrayList<>();
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        final long now = System.currentTimeMillis();

        if (user == null
                || !String.valueOf(sessionKey).equals(String.valueOf(request.getParameter("key")))
                || !String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
                || expiresAt == null
                || expiresAt.longValue() < now) {
            audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "BAD_REQUEST", null,
                "invalid_or_expired_force_change", request);
            clearForcedSequenceChange(session);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "GraphicalMatrixを変更できません。",
                "変更画面の有効期限が切れています。サービス画面からログインをやり直してください。");
            return;
        }

        final List<String> selected = GraphicalMatrixSupport.csv(request.getParameter("selected"));
        if (!validNewSequence(selected, displayOrder, config)) {
            audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "BAD_REQUEST", null,
                "invalid_new_sequence,selected_count=" + selected.size(), request);
            renderForcedSequenceChange(request, response, config, key, csrfToken, displayOrder,
                invalidNewSequenceMessage(config));
            return;
        }

        try {
            final GraphicalMatrixEnrollment current = repository.findEnrollment(user);
            if (current == null) {
                audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "ENROLL_REQUIRED", null,
                    "missing_enrollment", request);
                clearForcedSequenceChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixを変更できません。",
                    "このアカウントの登録情報を確認できません。管理者に連絡してください。");
                return;
            }

            if (repository.sameSequence(current.getSequence(), selected,
                    config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed())) {
                audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "BAD_REQUEST", null,
                    "same_sequence,selected_count=" + selected.size(), request);
                renderForcedSequenceChange(request, response, config, key, csrfToken, displayOrder,
                    "同じパスワードが選択されています。別のGraphicalMatrixを選択してください。");
                return;
            }

            if (!repository.updateSequence(user, selected, now,
                    config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed())) {
                audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "ENROLL_REQUIRED", null,
                    "missing_enrollment", request);
                clearForcedSequenceChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixを変更できません。",
                    "このアカウントの登録情報を確認できません。管理者に連絡してください。");
                return;
            }

            audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "OK", null,
                "sequence_count=" + selected.size(), request);
            request.setAttribute(ExternalAuthentication.PRINCIPAL_NAME_KEY, user);
            clearForcedSequenceChange(session);
            ExternalAuthentication.finishExternalAuthentication(key, request, response);
        } catch (Exception ex) {
            audit.log("FORCE_SEQUENCE_CHANGE_SAVE", user, "DB_ERROR", null,
                ex.getClass().getSimpleName(), request);
            throw new ServletException(ex);
        }
    }

    private static void clearForcedSequenceChange(final HttpSession session) {
        session.removeAttribute("forceSequence.key");
        session.removeAttribute("forceSequence.user");
        session.removeAttribute("forceSequence.csrfToken");
        session.removeAttribute("forceSequence.expiresAt");
        session.removeAttribute("forceSequence.displayOrder");
        session.removeAttribute("forceSequence.config");
    }

    private static boolean validNewSequence(final List<String> selected,
            final List<String> displayOrder, final GraphicalMatrixConfig config) {
        final java.util.Set<String> unique = new java.util.HashSet<>(selected);
        return selected.size() == config.getChoiceCount()
            && (config.isDuplicateSelectionsAllowed() || unique.size() == selected.size())
            && displayOrder.containsAll(selected)
            && config.containsAll(selected);
    }

    private static String invalidNewSequenceMessage(final GraphicalMatrixConfig config) {
        return config.isDuplicateSelectionsAllowed()
            ? "新しいGraphicalMatrixの選択が正しくありません。指定数の画像を選択してください。"
            : "新しいGraphicalMatrixの選択が正しくありません。指定数の画像を重複なしで選択してください。";
    }

    private static boolean sameSequence(final List<String> selected, final List<String> current,
            final GraphicalMatrixConfig config) {
        if (selected.size() != current.size()) {
            return false;
        }
        if (config.isOrderedSelectionRequired()) {
            return selected.equals(current);
        }
        if (config.isDuplicateSelectionsAllowed()) {
            return multiset(selected).equals(multiset(current));
        }
        return new java.util.HashSet<>(selected).equals(new java.util.HashSet<>(current));
    }

    private static java.util.Map<String, Integer> multiset(final List<String> values) {
        final java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (final String value : values) {
            out.put(value, out.getOrDefault(value, 0) + 1);
        }
        return out;
    }

    private static List<String> orderedGraphicalIds(final GraphicalMatrixConfig config) {
        return new ArrayList<>(config.getGraphicalIds());
    }

    private static void renderForcedSequenceChange(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final String key, final String csrfToken, final List<String> displayOrder,
            final String errorMessage) throws IOException {
        if (GraphicalMatrixViewRenderer.renderForcedSequenceChange(request, response, config, key,
                csrfToken, displayOrder, errorMessage)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix forced sequence change template is missing.");
    }

    private static boolean isRetryableFailure(final GraphicalMatrixVerifyResult result) {
        return "FAIL".equals(result.getAuditResult())
            && result.getAuditDetail() != null
            && result.getAuditDetail().startsWith("failed_count=");
    }

    private static String retryMessage(final GraphicalMatrixVerifyResult result) {
        final int failedCount = failedCount(result.getAuditDetail());
        final int remaining = MAX_FAILURES - failedCount;
        if (remaining > 0) {
            return "画像の順番が正しくありません。もう一度選択してください。あと"
                + remaining + "回間違えると一時的にロックされます。";
        }
        return "画像の順番が正しくありません。もう一度選択してください。";
    }

    private static int failedCount(final String auditDetail) {
        if (auditDetail == null || !auditDetail.startsWith("failed_count=")) {
            return 0;
        }
        final int comma = auditDetail.indexOf(',');
        final String value = (comma >= 0)
            ? auditDetail.substring("failed_count=".length(), comma)
            : auditDetail.substring("failed_count=".length());
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
