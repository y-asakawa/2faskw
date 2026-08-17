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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public final class GraphicalMatrixChangeServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final int MAX_LDAP_FAILURE_KEYS = 10_000;
    private static final int MAX_LDAP_FAILURE_IPS = 10_000;
    private static final GraphicalMatrixLdapLoginRateLimiter LDAP_FAILURES =
        new GraphicalMatrixLdapLoginRateLimiter(MAX_LDAP_FAILURE_KEYS);
    private static final GraphicalMatrixLdapLoginRateLimiter LDAP_IP_FAILURES =
        new GraphicalMatrixLdapLoginRateLimiter(MAX_LDAP_FAILURE_IPS);

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        noStore(response);

        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        if ("idp-self-service".equals(trim(request.getParameter("mode")))) {
            startFromSelfService(request, response, config);
            return;
        }

        final HttpSession session = request.getSession(false);
        if (session != null) {
            clearChange(session);
        }
        if (config.isSelfServiceEnabled() && !config.isLegacyLdapLoginEnabled()) {
            response.sendRedirect(request.getContextPath()
                + GraphicalMatrixSelfServiceAuthentication.PROFILE_PATH);
            return;
        }
        renderStart(request, response, config, null);
    }

    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        noStore(response);

        final String mode = trim(request.getParameter("mode"));
        final GraphicalMatrixConfig config = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        if ("ldap-login".equals(mode)) {
            ldapLogin(request, response, config);
            return;
        }
        if ("verify-current".equals(mode)) {
            verifyCurrent(request, response, config);
            return;
        }
        if ("choose-sequence".equals(mode)) {
            chooseSequence(request, response, config);
            return;
        }
        if ("choose-method".equals(mode)) {
            chooseMethod(request, response, config);
            return;
        }
        if ("back-menu".equals(mode)) {
            backMenu(request, response, config);
            return;
        }
        if ("save".equals(mode)) {
            saveNewSequence(request, response, config);
            return;
        }
        if ("save-method".equals(mode)) {
            saveMfaMethod(request, response, config);
            return;
        }

        renderStart(request, response, config, "操作を確認できません。最初からやり直してください。");
    }

    private static void ldapLogin(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final String user = trim(request.getParameter("user"));
        if (!config.isLegacyLdapLoginEnabled()) {
            audit.log("CHANGE_LDAP_AUTH", user, "DENIED", null, "legacy_ldap_login_disabled", request);
            if (config.isSelfServiceEnabled()) {
                response.sendRedirect(request.getContextPath()
                    + GraphicalMatrixSelfServiceAuthentication.PROFILE_PATH);
            } else {
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "変更画面を利用できません。", "管理者に連絡してください。");
            }
            return;
        }
        final String password = request.getParameter("password");
        if (!validUser(user) || password == null || password.isEmpty()) {
            audit.log("CHANGE_LDAP_AUTH", user, "BAD_REQUEST", null, "invalid_input", request);
            renderStart(request, response, config, "ユーザーIDまたはパスワードが正しくありません。");
            return;
        }
        if (ldapRateLimited(request, user, config)) {
            audit.log("CHANGE_LDAP_AUTH", user, "RATE_LIMITED", null,
                ldapRateLimitAuditDetail(
                    "ldap_failure_rate_limited,key=" + config.getChangeLdapRateLimitKey(),
                    request, config), request);
            renderStart(request, response, config,
                "認証に連続して失敗したため、一時的に制限されています。しばらくしてから再度試してください。");
            return;
        }

        final boolean authenticated;
        try {
            authenticated = new GraphicalMatrixLdapAuthenticator(GraphicalMatrixRuntime.idpHome())
                .authenticate(user, password);
        } catch (Exception ex) {
            audit.log("CHANGE_LDAP_AUTH", user, "LDAP_ERROR", null,
                ldapRateLimitAuditDetail(ex.getClass().getSimpleName(), request, config), request);
            renderStart(request, response, config,
                "LDAP認証を確認できません。時間をおいて再度試してください。");
            return;
        }

        if (!authenticated) {
            recordLdapFailure(request, user, config);
            audit.log("CHANGE_LDAP_AUTH", user, "FAIL", null,
                ldapRateLimitAuditDetail("bind_failed", request, config), request);
            renderStart(request, response, config, "ユーザーIDまたはパスワードが正しくありません。");
            return;
        }

        clearLdapFailures(request, user, config);
        audit.log("CHANGE_LDAP_AUTH", user, "OK", null,
            ldapRateLimitAuditDetail("bind_success", request, config), request);
        startCurrentChallenge(request, response, config, user, null);
    }

    private static void startFromSelfService(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final HttpSession session = request.getSession(false);
        final GraphicalMatrixSelfServiceSession.Handoff handoff =
            GraphicalMatrixSelfServiceSession.consume(session, System.currentTimeMillis());
        if (!config.isSelfServiceEnabled() || handoff == null) {
            audit.log("SELF_SERVICE_HANDOFF", null, "DENIED", null,
                config.isSelfServiceEnabled() ? "missing_or_expired" : "self_service_disabled", request);
            if (session != null) {
                clearChange(session);
            }
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "自己管理画面を開始できません。",
                "認証の有効期限が切れた可能性があります。最初からやり直してください。");
            return;
        }

        final String user = handoff.getUser();
        final GraphicalMatrixEnrollment enrollment;
        try {
            enrollment = GraphicalMatrixRuntime.repository().findEnrollment(user);
        } catch (Exception ex) {
            audit.log("SELF_SERVICE_HANDOFF", user, "DB_ERROR", null,
                ex.getClass().getSimpleName(), request);
            clearChange(session);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "登録情報を確認できません。",
                "時間をおいて再度試すか、管理者に連絡してください。");
            return;
        }

        final long now = System.currentTimeMillis();
        if (enrollment == null || !enrollment.isActive() || enrollment.getLockedUntil() > now) {
            audit.log("SELF_SERVICE_HANDOFF", user, "ENROLL_REQUIRED", null,
                "missing_inactive_or_locked", request);
            clearChange(session);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "GraphicalMatrixを変更できません。",
                "このアカウントの登録状態を確認できません。管理者に連絡してください。");
            return;
        }

        initializeVerifiedSession(session, config, user, enrollment, now);
        audit.log("SELF_SERVICE_HANDOFF", user, "OK", null, "one_time_handoff_consumed", request);
        renderMenu(request, response, config, user,
            (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken"), null);
    }

    static boolean ldapRateLimited(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEffective()) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final String key = ldapRateLimitKey(request, user, config);
        return (!ldapIpLimitBypassed(request.getRemoteAddr(), config)
                && LDAP_IP_FAILURES.isLimited(ldapIpRateLimitKey(request), now,
                    ldapIpRateLimitMaximumAge(config)))
            || LDAP_FAILURES.isLimited(key, now, ldapRateLimitMaximumAge(config));
    }

    static void recordLdapFailure(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEffective()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final String key = ldapRateLimitKey(request, user, config);
        if (!ldapIpLimitBypassed(request.getRemoteAddr(), config)) {
            LDAP_IP_FAILURES.recordFailure(ldapIpRateLimitKey(request), now,
                config.getChangeLdapRateLimitIpWindowMillis(),
                config.getChangeLdapRateLimitIpFailureLimit(),
                config.getChangeLdapRateLimitIpLockMillis(),
                ldapIpRateLimitMaximumAge(config));
        }
        LDAP_FAILURES.recordFailure(key, now, config.getChangeLdapRateLimitWindowMillis(),
            config.getChangeLdapRateLimitFailureLimit(), config.getChangeLdapRateLimitLockMillis(),
            ldapRateLimitMaximumAge(config));
    }

    private static void clearLdapFailures(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEffective()) {
            return;
        }
        LDAP_FAILURES.clear(ldapRateLimitKey(request, user, config));
    }

    private static long ldapRateLimitMaximumAge(final GraphicalMatrixConfig config) {
        return Math.max(config.getChangeLdapRateLimitWindowMillis(),
            config.getChangeLdapRateLimitLockMillis());
    }

    private static long ldapIpRateLimitMaximumAge(final GraphicalMatrixConfig config) {
        return Math.max(config.getChangeLdapRateLimitIpWindowMillis(),
            config.getChangeLdapRateLimitIpLockMillis());
    }

    private static String ldapIpRateLimitKey(final HttpServletRequest request) {
        return "ip:" + trim(request.getRemoteAddr());
    }

    static boolean ldapIpLimitBypassed(final String clientIp, final GraphicalMatrixConfig config) {
        return config.isChangeLdapRateLimitIpLimitBypassed(trim(clientIp));
    }

    private static String ldapRateLimitAuditDetail(final String detail,
            final HttpServletRequest request, final GraphicalMatrixConfig config) {
        return ldapIpLimitBypassed(request.getRemoteAddr(), config)
            ? detail + ",ip_limit=bypassed"
            : detail;
    }

    private static String ldapRateLimitKey(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        final String ip = trim(request.getRemoteAddr());
        final String normalizedUser = trim(user).toLowerCase(Locale.ROOT);
        switch (config.getChangeLdapRateLimitKey()) {
            case "ip":
                return "ip:" + ip;
            case "user":
                return "user:" + normalizedUser;
            case "ip-user":
            default:
                return "ip-user:" + ip + ":" + normalizedUser;
        }
    }

    private static void startCurrentChallenge(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config, final String user,
            final String errorMessage) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final GraphicalMatrixEnrollment enrollment;
        try {
            enrollment = repository.findEnrollment(user);
        } catch (Exception ex) {
            audit.log("CHANGE_START", user, "DB_ERROR", null, ex.getClass().getSimpleName(), request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "登録情報を確認できません。",
                "時間をおいて再度試すか、管理者に連絡してください。");
            return;
        }

        if (enrollment == null || !enrollment.isActive()) {
            audit.log("CHANGE_START", user, "ENROLL_REQUIRED", null, "missing_or_inactive", request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "GraphicalMatrixを変更できません。",
                "このアカウントのGraphicalMatrix登録情報を確認できません。管理者に連絡してください。");
            return;
        }

        if (!legacyGraphicalMatrixAllowed(enrollment)) {
            audit.log("CHANGE_START", user, "DENIED", null,
                "current_mfa_method_required,method=" + normalizeMethod(enrollment.getMfaMethod()), request);
            if (config.isSelfServiceEnabled()) {
                response.sendRedirect(request.getContextPath()
                    + GraphicalMatrixSelfServiceAuthentication.PROFILE_PATH);
            } else {
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "現在のMFA方式による再認証が必要です。",
                    "IdP自己管理フローを利用するか、管理者に連絡してください。");
            }
            return;
        }

        if (!repository.sequenceUsable(enrollment.getSequence(), config)) {
            final int sequenceCount = repository.sequenceCount(enrollment.getSequence());
            audit.log("CHANGE_START", user, "ENROLL_REQUIRED", null,
                "sequence_mismatch,sequence_count=" + sequenceCount, request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "GraphicalMatrixを変更できません。",
                "登録済みのGraphicalMatrixが現在の設定と一致していません。管理者に連絡してください。");
            return;
        }

        final long now = System.currentTimeMillis();
        if (enrollment.getLockedUntil() > now) {
            audit.log("CHANGE_START", user, "LOCKED", null,
                "locked_until=" + enrollment.getLockedUntil(), request);
            GraphicalMatrixStartServlet.renderLocked(request, response, enrollment.getLockedUntil());
            return;
        }

        final List<String> displayOrder = GraphicalMatrixSupport.shuffledGraphicalIds(config);
        final String challengeId = GraphicalMatrixSupport.token();
        final String csrfToken = GraphicalMatrixSupport.token();
        final HttpSession session = request.getSession();
        clearChange(session);
        session.setAttribute("graphicalmatrixChange.user", user);
        session.setAttribute("graphicalmatrixChange.challengeId", challengeId);
        session.setAttribute("graphicalmatrixChange.csrfToken", csrfToken);
        session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
        session.setAttribute("graphicalmatrixChange.displayOrder", displayOrder);
        session.setAttribute("graphicalmatrixChange.config", config);
        session.setAttribute("graphicalmatrixChange.used", Boolean.FALSE);

        audit.log("CHANGE_CHALLENGE_CREATED", user, "OK", challengeId,
            "graphicals=" + displayOrder.size() + ",choice=" + config.getChoiceCount(), request);
        renderCurrent(request, response, config, user, challengeId, csrfToken, displayOrder, errorMessage);
    }

    private static void verifyCurrent(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig requestConfig) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("CHANGE_VERIFY", null, "BAD_REQUEST", null, "session_missing", request);
            renderStart(request, response, requestConfig, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrixChange.user");
        final String challengeId = (String) session.getAttribute("graphicalmatrixChange.challengeId");
        final String csrfToken = (String) session.getAttribute("graphicalmatrixChange.csrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrixChange.expiresAt");
        final Boolean used = (Boolean) session.getAttribute("graphicalmatrixChange.used");
        final Object displayOrderObject = session.getAttribute("graphicalmatrixChange.displayOrder");
        final Object configObject = session.getAttribute("graphicalmatrixChange.config");

        @SuppressWarnings("unchecked")
        final List<String> displayOrder = (displayOrderObject instanceof List)
            ? (List<String>) displayOrderObject : new ArrayList<>();
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : requestConfig;

        final long now = System.currentTimeMillis();
        GraphicalMatrixVerifyResult result = GraphicalMatrixVerifyResult.failed("invalid_or_expired_challenge");
        if (user != null
                && user.equals(trim(request.getParameter("user")))
                && String.valueOf(challengeId).equals(String.valueOf(request.getParameter("challengeId")))
                && String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
                && expiresAt != null
                && expiresAt.longValue() >= now
                && !Boolean.TRUE.equals(used)) {
            session.setAttribute("graphicalmatrixChange.used", Boolean.TRUE);
            result = repository.verifyForSequenceChange(
                user,
                GraphicalMatrixSupport.csv(request.getParameter("selected")),
                displayOrder,
                now,
                config.getLockoutPolicy(),
                config.isOrderedSelectionRequired(),
                config.isDuplicateSelectionsAllowed()
            );
        }

        audit.log("CHANGE_VERIFY", user, result.getAuditResult(), challengeId, result.getAuditDetail(), request);

        if (result.isSuccess()) {
            final GraphicalMatrixEnrollment verifiedEnrollment;
            try {
                verifiedEnrollment = repository.findEnrollment(user);
            } catch (Exception ex) {
                audit.log("CHANGE_VERIFY", user, "DB_ERROR", challengeId,
                    ex.getClass().getSimpleName(), request);
                clearChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "登録情報を確認できません。",
                    "時間をおいて再度試すか、管理者に連絡してください。");
                return;
            }
            if (verifiedEnrollment == null || !verifiedEnrollment.isActive()
                    || verifiedEnrollment.getLockedUntil() > now) {
                audit.log("CHANGE_VERIFY", user, "ENROLL_REQUIRED", challengeId,
                    "state_changed_after_verification", request);
                clearChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixを変更できません。",
                    "登録状態が変更されました。最初からやり直してください。");
                return;
            }
            initializeVerifiedSession(session, config, user, verifiedEnrollment, now);
            final String saveCsrfToken =
                (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken");
            renderMenu(request, response, config, user, saveCsrfToken, null);
            return;
        }

        if (isRetryableFailure(result)) {
            startCurrentChallenge(request, response, config, user, retryMessage(result, config));
            return;
        }

        if ("LOCKED".equals(result.getAuditResult())) {
            clearChange(session);
            GraphicalMatrixStartServlet.renderLocked(
                request, response, result.getLockedUntil());
            return;
        }

        clearChange(session);
        renderStart(request, response, config, "本人確認に失敗しました。最初からやり直してください。");
    }

    private static void chooseSequence(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig requestConfig) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("CHANGE_CHOOSE_SEQUENCE", null, "BAD_REQUEST", null, "session_missing", request);
            renderStart(request, response, requestConfig, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrixChange.user");
        final String csrfToken = (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrixChange.expiresAt");
        final Boolean verified = (Boolean) session.getAttribute("graphicalmatrixChange.verified");
        final Object configObject = session.getAttribute("graphicalmatrixChange.config");
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : requestConfig;
        final long now = System.currentTimeMillis();

        if (!validVerifiedSession(user, verified, csrfToken, expiresAt, request, now)) {
            audit.log("CHANGE_CHOOSE_SEQUENCE", user, "BAD_REQUEST", null, "invalid_or_expired_choose", request);
            clearChange(session);
            renderStart(request, response, config, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final List<String> newDisplayOrder = orderedGraphicalIds(config);
        session.setAttribute("graphicalmatrixChange.newDisplayOrder", newDisplayOrder);
        session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
        renderNew(request, response, config, user, csrfToken, newDisplayOrder, null);
    }

    private static void backMenu(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig requestConfig) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("CHANGE_BACK_MENU", null, "BAD_REQUEST", null, "session_missing", request);
            renderStart(request, response, requestConfig, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrixChange.user");
        final String csrfToken = (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrixChange.expiresAt");
        final Boolean verified = (Boolean) session.getAttribute("graphicalmatrixChange.verified");
        final Object configObject = session.getAttribute("graphicalmatrixChange.config");
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : requestConfig;
        final long now = System.currentTimeMillis();

        if (!validVerifiedSession(user, verified, csrfToken, expiresAt, request, now)) {
            audit.log("CHANGE_BACK_MENU", user, "BAD_REQUEST", null, "invalid_or_expired_back", request);
            clearChange(session);
            renderStart(request, response, config, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        session.removeAttribute("graphicalmatrixChange.newDisplayOrder");
        session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
        audit.log("CHANGE_BACK_MENU", user, "OK", null, "back_to_menu", request);
        renderMenu(request, response, config, user, csrfToken, null);
    }

    private static void chooseMethod(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig requestConfig) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("CHANGE_CHOOSE_METHOD", null, "BAD_REQUEST", null, "session_missing", request);
            renderStart(request, response, requestConfig, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrixChange.user");
        final String csrfToken = (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrixChange.expiresAt");
        final Boolean verified = (Boolean) session.getAttribute("graphicalmatrixChange.verified");
        final Object configObject = session.getAttribute("graphicalmatrixChange.config");
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : requestConfig;
        final long now = System.currentTimeMillis();

        if (!validVerifiedSession(user, verified, csrfToken, expiresAt, request, now)) {
            audit.log("CHANGE_CHOOSE_METHOD", user, "BAD_REQUEST", null, "invalid_or_expired_choose", request);
            clearChange(session);
            renderStart(request, response, config, "有効期限が切れています。最初からやり直してください。");
            return;
        }
        if (!mfaMethodChangeAllowed(session)) {
            audit.log("CHANGE_CHOOSE_METHOD", user, "BAD_REQUEST", null,
                "force_sequence_change_required", request);
            session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
            renderMenu(request, response, config, user, csrfToken,
                "GraphicalMatrixの変更が必要です。MFA方式を変更する前に、新しいGraphicalMatrixを登録してください。");
            return;
        }

        final String currentMethod;
        try {
            currentMethod = repository.findMfaMethod(user);
        } catch (Exception ex) {
            audit.log("CHANGE_CHOOSE_METHOD", user, "DB_ERROR", null, ex.getClass().getSimpleName(), request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "MFA方式を確認できません。",
                "時間をおいて再度試すか、管理者に連絡してください。");
            return;
        }

        session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
        renderMethod(request, response, config, user, csrfToken, currentMethod, null);
    }

    private static void saveNewSequence(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig requestConfig) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("CHANGE_SAVE", null, "BAD_REQUEST", null, "session_missing", request);
            renderStart(request, response, requestConfig, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrixChange.user");
        final String csrfToken = (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrixChange.expiresAt");
        final Boolean verified = (Boolean) session.getAttribute("graphicalmatrixChange.verified");
        final Long stateVersion = (Long) session.getAttribute("graphicalmatrixChange.stateVersion");
        final Object displayOrderObject = session.getAttribute("graphicalmatrixChange.newDisplayOrder");
        final Object configObject = session.getAttribute("graphicalmatrixChange.config");

        @SuppressWarnings("unchecked")
        final List<String> displayOrder = (displayOrderObject instanceof List)
            ? (List<String>) displayOrderObject : GraphicalMatrixSupport.shuffledGraphicalIds(requestConfig);
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : requestConfig;

        final long now = System.currentTimeMillis();
        if (user == null
                || !user.equals(trim(request.getParameter("user")))
                || !Boolean.TRUE.equals(verified)
                || stateVersion == null
                || !String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
                || expiresAt == null
                || expiresAt.longValue() < now) {
            audit.log("CHANGE_SAVE", user, "BAD_REQUEST", null, "invalid_or_expired_save", request);
            clearChange(session);
            renderStart(request, response, config, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final List<String> selected = GraphicalMatrixSupport.csv(request.getParameter("selected"));
        if (!validNewSequence(selected, displayOrder, config)) {
            audit.log("CHANGE_SAVE", user, "BAD_REQUEST", null,
                "invalid_new_sequence,selected_count=" + selected.size(), request);
            renderNew(request, response, config, user, csrfToken, displayOrder,
                invalidNewSequenceMessage(config));
            return;
        }

        try {
            final GraphicalMatrixEnrollment current = repository.findEnrollment(user);
            if (current == null) {
                audit.log("CHANGE_SAVE", user, "ENROLL_REQUIRED", null, "missing_enrollment", request);
                clearChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixを変更できません。",
                    "このアカウントの登録情報を確認できません。管理者に連絡してください。");
                return;
            }

            if (repository.sameSequence(current.getSequence(), selected,
                    config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed())) {
                audit.log("CHANGE_SAVE", user, "BAD_REQUEST", null,
                    "same_sequence,selected_count=" + selected.size(), request);
                renderNew(request, response, config, user, csrfToken, displayOrder,
                    "同じパスワードです。別のGraphicalMatrixを選択してください。");
                return;
            }

            if (!repository.updateSequence(user, selected, now, stateVersion.longValue(),
                    config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed())) {
                audit.log("CHANGE_SAVE", user, "ENROLL_REQUIRED", null,
                    "enrollment_state_changed", request);
                clearChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "GraphicalMatrixを変更できません。",
                    "このアカウントの登録情報を確認できません。管理者に連絡してください。");
                return;
            }
        } catch (Exception ex) {
            audit.log("CHANGE_SAVE", user, "DB_ERROR", null, ex.getClass().getSimpleName(), request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "GraphicalMatrixを変更できません。",
                "時間をおいて再度試すか、管理者に連絡してください。");
            return;
        }

        audit.log("CHANGE_SAVE", user, "OK", null,
            "sequence_count=" + selected.size() + ",order_mode=ordered_storage", request);
        if (Boolean.TRUE.equals(session.getAttribute("graphicalmatrixChange.forceSequenceRequired"))) {
            session.setAttribute("graphicalmatrixChange.forceSequenceRequired", Boolean.FALSE);
            session.setAttribute("graphicalmatrixChange.sequenceChanged", Boolean.TRUE);
            session.setAttribute("graphicalmatrixChange.stateVersion", Long.valueOf(stateVersion.longValue() + 1L));
            session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
            session.removeAttribute("graphicalmatrixChange.newDisplayOrder");
            renderMenu(request, response, config, user, csrfToken,
                "GraphicalMatrixを変更しました。必要に応じてMFA方式を変更してください。");
            return;
        }
        clearChange(session);
        renderComplete(request, response, config, user,
            "新しいGraphicalMatrixを登録しました。次回ログインから新しい画像を利用してください。");
    }

    private static void saveMfaMethod(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig requestConfig) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixRepository repository = GraphicalMatrixRuntime.repository();
        final HttpSession session = request.getSession(false);
        if (session == null) {
            audit.log("CHANGE_METHOD_SAVE", null, "BAD_REQUEST", null, "session_missing", request);
            renderStart(request, response, requestConfig, "有効期限が切れています。最初からやり直してください。");
            return;
        }

        final String user = (String) session.getAttribute("graphicalmatrixChange.user");
        final String csrfToken = (String) session.getAttribute("graphicalmatrixChange.saveCsrfToken");
        final Long expiresAt = (Long) session.getAttribute("graphicalmatrixChange.expiresAt");
        final Boolean verified = (Boolean) session.getAttribute("graphicalmatrixChange.verified");
        final Long expectedStateVersion = (Long) session.getAttribute("graphicalmatrixChange.stateVersion");
        final Object configObject = session.getAttribute("graphicalmatrixChange.config");
        final GraphicalMatrixConfig config = (configObject instanceof GraphicalMatrixConfig)
            ? (GraphicalMatrixConfig) configObject : requestConfig;
        final long now = System.currentTimeMillis();

        if (!validVerifiedSession(user, verified, csrfToken, expiresAt, request, now)) {
            audit.log("CHANGE_METHOD_SAVE", user, "BAD_REQUEST", null, "invalid_or_expired_save", request);
            clearChange(session);
            renderStart(request, response, config, "有効期限が切れています。最初からやり直してください。");
            return;
        }
        if (expectedStateVersion == null) {
            audit.log("CHANGE_METHOD_SAVE", user, "BAD_REQUEST", null, "state_version_missing", request);
            clearChange(session);
            renderStart(request, response, config, "登録状態を確認できません。最初からやり直してください。");
            return;
        }
        if (!mfaMethodChangeAllowed(session)) {
            audit.log("CHANGE_METHOD_SAVE", user, "BAD_REQUEST", null,
                "force_sequence_change_required", request);
            session.setAttribute("graphicalmatrixChange.expiresAt", Long.valueOf(now + config.getChallengeMillis()));
            renderMenu(request, response, config, user, csrfToken,
                "GraphicalMatrixの変更が必要です。MFA方式を変更する前に、新しいGraphicalMatrixを登録してください。");
            return;
        }

        final String method = normalizeMethod(request.getParameter("mfaMethod"));
        if (!"GraphicalMatrix".equals(method) && !"TOTP".equals(method)
                && !"WebAuthn".equals(method)) {
            audit.log("CHANGE_METHOD_SAVE", user, "BAD_REQUEST", null, "invalid_method", request);
            renderMethod(request, response, config, user, csrfToken, method,
                "選択できないMFA方式です。");
            return;
        }

        if ("WebAuthn".equals(method)) {
            beginWebAuthnRegistration(request, response, config, repository, session,
                user, expectedStateVersion.longValue(), now);
            return;
        }

        try {
            if (!repository.updateMfaMethodIfCurrent(user, method, now, expectedStateVersion.longValue())) {
                audit.log("CHANGE_METHOD_SAVE", user, "ENROLL_REQUIRED", null,
                    "state_changed_after_verification", request);
                clearChange(session);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "MFA方式を変更できません。",
                    "登録状態が変更されました。最初からやり直してください。");
                return;
            }
        } catch (Exception ex) {
            audit.log("CHANGE_METHOD_SAVE", user, "DB_ERROR", null, ex.getClass().getSimpleName(), request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "MFA方式を変更できません。",
                "時間をおいて再度試すか、管理者に連絡してください。");
            return;
        }

        audit.log("CHANGE_METHOD_SAVE", user, "OK", null, "mfa_method=" + method, request);
        if ("TOTP".equals(method)) {
            try {
                final String seed = repository.prepareTotpRegistration(user, now);
                if (seed == null || seed.isEmpty()) {
                    clearChange(session);
                    audit.log("TOTP_REGISTER_START", user, "ENROLL_REQUIRED", null,
                        "totp_registration_unavailable", request);
                    GraphicalMatrixStartServlet.renderUnavailable(request, response,
                        "TOTP登録を開始できません。",
                        "登録状態が変更されました。最初からやり直してください。");
                    return;
                }
                final String enrollmentKey = GraphicalMatrixSupport.token();
                final String enrollmentCsrf = GraphicalMatrixSupport.token();
                clearChange(session);
                session.setAttribute("totpEnroll.key", enrollmentKey);
                session.setAttribute("totpEnroll.user", user);
                session.setAttribute("totpEnroll.csrfToken", enrollmentCsrf);
                session.setAttribute("totpEnroll.expiresAt",
                    Long.valueOf(now + config.getChallengeMillis()));
                session.setAttribute("totpEnroll.used", Boolean.FALSE);
                session.setAttribute("totpEnroll.selfServiceAuthorized", Boolean.TRUE);
                audit.log("TOTP_REGISTER_START", user, "OK", null,
                    "authorization=self_service", request);
                GraphicalMatrixStartServlet.renderTotpRegistration(request, response,
                    enrollmentKey, user, seed, enrollmentCsrf, null);
                return;
            } catch (Exception ex) {
                clearChange(session);
                audit.log("TOTP_REGISTER_START", user, "DB_ERROR", null,
                    ex.getClass().getSimpleName(), request);
                GraphicalMatrixStartServlet.renderUnavailable(request, response,
                    "TOTP登録を開始できません。",
                    "時間をおいて再度試すか、管理者に連絡してください。");
                return;
            }
        }
        clearChange(session);
        renderComplete(request, response, config, user,
            "MFA方式をGraphicalMatrixに変更しました。次回ログインからGraphicalMatrixを利用します。");
    }

    private static void beginWebAuthnRegistration(final HttpServletRequest request,
            final HttpServletResponse response, final GraphicalMatrixConfig config,
            final GraphicalMatrixRepository repository, final HttpSession session,
            final String user, final long expectedStateVersion, final long now) throws IOException {
        final GraphicalMatrixAuditLogger audit = GraphicalMatrixRuntime.auditLogger();
        final GraphicalMatrixEnrollment enrollment;
        try {
            enrollment = repository.findEnrollment(user);
        } catch (Exception ex) {
            audit.log("WEBAUTHN_REGISTER_START", user, "DB_ERROR", null,
                ex.getClass().getSimpleName(), request);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "WebAuthn登録を開始できません。",
                "時間をおいて再度試すか、管理者に連絡してください。");
            return;
        }
        if (enrollment == null || !enrollment.isActive()
                || enrollment.getLockedUntil() > now
                || enrollment.getStateVersion() != expectedStateVersion) {
            audit.log("WEBAUTHN_REGISTER_START", user, "ENROLL_REQUIRED", null,
                "state_changed_after_verification", request);
            clearChange(session);
            GraphicalMatrixStartServlet.renderUnavailable(request, response,
                "WebAuthn登録を開始できません。",
                "登録状態が変更されました。最初からやり直してください。");
            return;
        }

        final String expectedMethod = enrollment.getMfaMethod();
        clearChange(session);
        GraphicalMatrixWebAuthnRegistrationSession.initialize(session, user, expectedMethod,
            now + config.getSelfServiceTransactionMillis());
        audit.log("WEBAUTHN_REGISTER_START", user, "OK", null,
            "authorization=verified_change_session,current_method="
                + normalizeMethod(expectedMethod), request);
        response.sendRedirect(response.encodeRedirectURL(request.getContextPath()
            + GraphicalMatrixWebAuthnRegistrationSession.REGISTRATION_PATH));
    }

    private static boolean validNewSequence(final List<String> selected,
            final List<String> displayOrder, final GraphicalMatrixConfig config) {
        final Set<String> unique = new HashSet<>(selected);
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
        return new HashSet<>(selected).equals(new HashSet<>(current));
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

    private static void renderStart(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixConfig config, final String errorMessage) throws IOException {
        if (config.isSelfServiceEnabled() && !config.isLegacyLdapLoginEnabled()) {
            response.sendRedirect(request.getContextPath()
                + GraphicalMatrixSelfServiceAuthentication.PROFILE_PATH);
            return;
        }
        if (GraphicalMatrixViewRenderer.renderSequenceChangeStart(request, response, config, errorMessage)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix change template is missing.");
    }

    private static void renderCurrent(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixConfig config, final String user, final String challengeId,
            final String csrfToken, final List<String> displayOrder, final String errorMessage)
            throws IOException {
        if (GraphicalMatrixViewRenderer.renderSequenceChangeCurrent(request, response, config, user,
                challengeId, csrfToken, displayOrder, errorMessage)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix current sequence template is missing.");
    }

    private static void renderNew(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixConfig config, final String user, final String csrfToken,
            final List<String> displayOrder, final String errorMessage) throws IOException {
        if (GraphicalMatrixViewRenderer.renderSequenceChangeNew(request, response, config, user,
                csrfToken, displayOrder, errorMessage)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix new sequence template is missing.");
    }

    private static void renderMenu(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixConfig config, final String user, final String csrfToken,
            final String errorMessage) throws IOException {
        final HttpSession session = request.getSession(false);
        final boolean methodChangeAllowed = session == null || mfaMethodChangeAllowed(session);
        if (GraphicalMatrixViewRenderer.renderSequenceChangeMenu(request, response, config, user,
                csrfToken, methodChangeAllowed, errorMessage)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix change menu template is missing.");
    }

    private static void renderMethod(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixConfig config, final String user, final String csrfToken,
            final String currentMethod, final String errorMessage) throws IOException {
        if (GraphicalMatrixViewRenderer.renderMfaMethodChange(request, response, config, user,
                csrfToken, currentMethod, errorMessage)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix MFA method template is missing.");
    }

    static void renderComplete(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixConfig config, final String user, final String message) throws IOException {
        if (GraphicalMatrixViewRenderer.renderSequenceChangeComplete(request, response, config, user, message)) {
            return;
        }
        response.sendError(500, "GraphicalMatrix change complete template is missing.");
    }

    private static void clearChange(final HttpSession session) {
        session.removeAttribute("graphicalmatrixChange.user");
        session.removeAttribute("graphicalmatrixChange.challengeId");
        session.removeAttribute("graphicalmatrixChange.csrfToken");
        session.removeAttribute("graphicalmatrixChange.expiresAt");
        session.removeAttribute("graphicalmatrixChange.displayOrder");
        session.removeAttribute("graphicalmatrixChange.newDisplayOrder");
        session.removeAttribute("graphicalmatrixChange.config");
        session.removeAttribute("graphicalmatrixChange.used");
        session.removeAttribute("graphicalmatrixChange.verified");
        session.removeAttribute("graphicalmatrixChange.stateVersion");
        session.removeAttribute("graphicalmatrixChange.saveCsrfToken");
        session.removeAttribute("graphicalmatrixChange.forceSequenceRequired");
        session.removeAttribute("graphicalmatrixChange.sequenceChanged");
    }

    private static void initializeVerifiedSession(final HttpSession session,
            final GraphicalMatrixConfig config, final String user,
            final GraphicalMatrixEnrollment enrollment, final long now) {
        clearChange(session);
        session.setAttribute("graphicalmatrixChange.user", user);
        session.setAttribute("graphicalmatrixChange.config", config);
        session.setAttribute("graphicalmatrixChange.verified", Boolean.TRUE);
        session.setAttribute("graphicalmatrixChange.stateVersion",
            Long.valueOf(enrollment.getStateVersion()));
        session.setAttribute("graphicalmatrixChange.saveCsrfToken", GraphicalMatrixSupport.token());
        session.setAttribute("graphicalmatrixChange.forceSequenceRequired",
            Boolean.valueOf(enrollment.isForceSequenceChange()));
        session.setAttribute("graphicalmatrixChange.sequenceChanged", Boolean.FALSE);
        session.setAttribute("graphicalmatrixChange.expiresAt",
            Long.valueOf(now + config.getChallengeMillis()));
    }

    private static boolean validVerifiedSession(final String user, final Boolean verified,
            final String csrfToken, final Long expiresAt, final HttpServletRequest request, final long now) {
        return user != null
            && user.equals(trim(request.getParameter("user")))
            && Boolean.TRUE.equals(verified)
            && String.valueOf(csrfToken).equals(String.valueOf(request.getParameter("csrfToken")))
            && expiresAt != null
            && expiresAt.longValue() >= now;
    }

    private static boolean mfaMethodChangeAllowed(final HttpSession session) {
        return !Boolean.TRUE.equals(session.getAttribute("graphicalmatrixChange.forceSequenceRequired"))
            || Boolean.TRUE.equals(session.getAttribute("graphicalmatrixChange.sequenceChanged"));
    }

    private static void noStore(final HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }

    private static boolean isRetryableFailure(final GraphicalMatrixVerifyResult result) {
        return "FAIL".equals(result.getAuditResult())
            && result.getAuditDetail() != null
            && result.getAuditDetail().startsWith("failed_count=");
    }

    private static String retryMessage(final GraphicalMatrixVerifyResult result,
            final GraphicalMatrixConfig config) {
        final int failedCount = failedCount(result.getAuditDetail());
        final int remaining = config.getLockoutFailureLimit() - failedCount;
        if (remaining > 0) {
            return "現在のGraphicalMatrixが正しくありません。あと"
                + remaining + "回間違えると一時的にロックされます。";
        }
        return "現在のGraphicalMatrixが正しくありません。";
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

    static boolean validUser(final String user) {
        return user != null && user.length() <= 255 && user.matches("[A-Za-z0-9._@-]+");
    }

    static boolean legacyGraphicalMatrixAllowed(final GraphicalMatrixEnrollment enrollment) {
        return enrollment != null
            && "GraphicalMatrix".equals(normalizeMethod(enrollment.getMfaMethod()));
    }

    private static String normalizeMethod(final String method) {
        final String value = trim(method);
        if ("GraphicalMatrix".equalsIgnoreCase(value) || "MFA:GraphicalMatrix".equalsIgnoreCase(value)) {
            return "GraphicalMatrix";
        }
        if ("TOTP".equalsIgnoreCase(value) || "MFA:TOTP".equalsIgnoreCase(value)) {
            return "TOTP";
        }
        if ("WebAuthn".equalsIgnoreCase(value) || "MFA:WebAuthn".equalsIgnoreCase(value)) {
            return "WebAuthn";
        }
        return value;
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }

}
