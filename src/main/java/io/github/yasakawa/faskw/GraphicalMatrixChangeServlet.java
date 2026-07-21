package io.github.yasakawa.faskw;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public final class GraphicalMatrixChangeServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 15L * 60L * 1000L;
    private static final Map<String, RateLimitState> LDAP_FAILURES = new ConcurrentHashMap<>();

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
                "ldap_failure_rate_limited,key=" + config.getChangeLdapRateLimitKey(), request);
            renderStart(request, response, config,
                "認証に連続して失敗したため、一時的に制限されています。しばらくしてから再度試してください。");
            return;
        }

        final boolean authenticated;
        try {
            authenticated = new GraphicalMatrixLdapAuthenticator(GraphicalMatrixRuntime.idpHome())
                .authenticate(user, password);
        } catch (Exception ex) {
            audit.log("CHANGE_LDAP_AUTH", user, "LDAP_ERROR", null, ex.getClass().getSimpleName(), request);
            renderStart(request, response, config,
                "LDAP認証を確認できません。時間をおいて再度試してください。");
            return;
        }

        if (!authenticated) {
            recordLdapFailure(request, user, config);
            audit.log("CHANGE_LDAP_AUTH", user, "FAIL", null, "bind_failed", request);
            renderStart(request, response, config, "ユーザーIDまたはパスワードが正しくありません。");
            return;
        }

        clearLdapFailures(request, user, config);
        audit.log("CHANGE_LDAP_AUTH", user, "OK", null, "bind_success", request);
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

    private static boolean ldapRateLimited(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEffective()) {
            return false;
        }
        final String key = ldapRateLimitKey(request, user, config);
        final RateLimitState state = LDAP_FAILURES.get(key);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            final long now = System.currentTimeMillis();
            if (state.lockedUntilMillis > now) {
                return true;
            }
            if (state.lockedUntilMillis > 0L) {
                LDAP_FAILURES.remove(key, state);
            }
            return false;
        }
    }

    private static void recordLdapFailure(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEffective()) {
            return;
        }
        cleanupLdapFailures(config);
        final long now = System.currentTimeMillis();
        final String key = ldapRateLimitKey(request, user, config);
        final RateLimitState state = LDAP_FAILURES.computeIfAbsent(key, ignored -> new RateLimitState());
        synchronized (state) {
            while (!state.failures.isEmpty()
                    && now - state.failures.peekFirst().longValue() > config.getChangeLdapRateLimitWindowMillis()) {
                state.failures.removeFirst();
            }
            state.failures.addLast(Long.valueOf(now));
            state.lastSeenMillis = now;
            if (state.failures.size() >= config.getChangeLdapRateLimitFailureLimit()) {
                state.lockedUntilMillis = now + config.getChangeLdapRateLimitLockMillis();
                state.failures.clear();
            }
        }
    }

    private static void clearLdapFailures(final HttpServletRequest request, final String user,
            final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEffective()) {
            return;
        }
        LDAP_FAILURES.remove(ldapRateLimitKey(request, user, config));
    }

    private static void cleanupLdapFailures(final GraphicalMatrixConfig config) {
        if (LDAP_FAILURES.size() < 10000) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long maxAge = Math.max(config.getChangeLdapRateLimitWindowMillis(),
            config.getChangeLdapRateLimitLockMillis());
        LDAP_FAILURES.entrySet().removeIf(entry -> {
            final RateLimitState state = entry.getValue();
            synchronized (state) {
                return state.lockedUntilMillis <= now && now - state.lastSeenMillis > maxAge;
            }
        });
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
                MAX_FAILURES,
                LOCK_MILLIS,
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
            startCurrentChallenge(request, response, config, user, retryMessage(result));
            return;
        }

        if ("LOCKED".equals(result.getAuditResult())) {
            clearChange(session);
            GraphicalMatrixStartServlet.renderLocked(request, response, now + LOCK_MILLIS);
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
        clearChange(session);
        final String message;
        if ("TOTP".equals(method)) {
            message = "MFA方式をTOTPに変更しました。次回ログイン時にQRコード登録画面が表示されます。";
        } else if ("WebAuthn".equals(method)) {
            message = "MFA方式をWebAuthnに変更しました。次回ログインからWebAuthnを利用します。";
        } else {
            message = "MFA方式をGraphicalMatrixに変更しました。次回ログインからGraphicalMatrixを利用します。";
        }
        renderComplete(request, response, config, user, message);
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

    private static void renderComplete(final HttpServletRequest request, final HttpServletResponse response,
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

    private static String retryMessage(final GraphicalMatrixVerifyResult result) {
        final int failedCount = failedCount(result.getAuditDetail());
        final int remaining = MAX_FAILURES - failedCount;
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

    private static boolean validUser(final String user) {
        return user.matches("[A-Za-z0-9._@-]+");
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

    private static final class RateLimitState {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long lockedUntilMillis;
        private long lastSeenMillis = System.currentTimeMillis();
    }
}
