package io.github.yasakawa.faskw;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class GraphicalMatrixAdminApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,255}");
    private static final Pattern JSON_STRING = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_ARRAY = Pattern.compile("\"%s\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern JSON_BOOLEAN = Pattern.compile("\"%s\"\\s*:\\s*(true|false|\"[^\"]*\")", Pattern.CASE_INSENSITIVE);
    private static final Map<String, RateLimitState> AUTH_FAILURES = new ConcurrentHashMap<>();
    private static final AtomicBoolean SCHEMA_INITIALIZED = new AtomicBoolean(false);

    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        if ("PATCH".equalsIgnoreCase(request.getMethod())) {
            doPatch(request, response);
            return;
        }
        super.service(request, response);
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        handle(request, response, "GET");
    }

    @Override
    protected void doPut(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        handle(request, response, "PUT");
    }

    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        handle(request, response, "POST");
    }

    @Override
    protected void doDelete(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        handle(request, response, "DELETE");
    }

    @Override
    protected void doPatch(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        handle(request, response, "PATCH");
    }

    private void handle(final HttpServletRequest request, final HttpServletResponse response,
            final String method) throws IOException {
        noStore(response);
        final GraphicalMatrixApiConfig apiConfig;
        try {
            apiConfig = GraphicalMatrixApiConfig.load(GraphicalMatrixRuntime.idpHome());
        } catch (Exception ex) {
            json(response, 500, "{\"error\":\"CONFIG_ERROR\",\"detail\":\"" + json(ex.getClass().getSimpleName()) + "\"}");
            return;
        }

        if (!authorize(request, response, apiConfig)) {
            return;
        }

        final GraphicalMatrixConfig matrixConfig;
        try {
            matrixConfig = GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
        } catch (Exception ex) {
            json(response, 500, "{\"error\":\"CONFIG_ERROR\",\"detail\":\"" + json(ex.getClass().getSimpleName()) + "\"}");
            return;
        }

        final List<String> path = path(request);
        try {
            if ("GET".equals(method) && path.size() == 1 && "health".equals(path.get(0))) {
                health(response);
                return;
            }
            if ("GET".equals(method) && path.size() == 1 && "graphicals".equals(path.get(0))) {
                graphicals(response, matrixConfig);
                return;
            }
            if (path.size() >= 2 && "users".equals(path.get(0))) {
                users(request, response, method, path, matrixConfig);
                return;
            }
            json(response, 404, "{\"error\":\"NOT_FOUND\"}");
        } catch (ApiException ex) {
            audit(request, ex.event, ex.user, ex.result, ex.detail);
            json(response, ex.status, "{\"error\":\"" + json(ex.code) + "\",\"detail\":\"" + json(ex.detail) + "\"}");
        } catch (Exception ex) {
            audit(request, "API_ERROR", path.size() >= 2 ? path.get(1) : "-", "ERROR", ex.getClass().getSimpleName());
            json(response, 500, "{\"error\":\"INTERNAL_ERROR\",\"detail\":\"" + json(ex.getClass().getSimpleName()) + "\"}");
        }
    }

    private static boolean authorize(final HttpServletRequest request, final HttpServletResponse response,
            final GraphicalMatrixApiConfig config) throws IOException {
        if (!config.isEnabled()) {
            audit(request, "API_DENIED", "-", "DISABLED", "api_disabled");
            json(response, 404, "{\"error\":\"NOT_FOUND\"}");
            return false;
        }
        if (!config.isTokenConfigured()) {
            audit(request, "API_DENIED", "-", "CONFIG_ERROR", "token_missing");
            json(response, 503, "{\"error\":\"API_TOKEN_NOT_CONFIGURED\"}");
            return false;
        }
        if (!config.allowedIp(request.getRemoteAddr())) {
            audit(request, "API_DENIED", "-", "FORBIDDEN", "ip_not_allowed");
            json(response, 403, "{\"error\":\"FORBIDDEN\"}");
            return false;
        }
        if (authRateLimited(request.getRemoteAddr(), config)) {
            audit(request, "API_DENIED", "-", "RATE_LIMITED", "bearer_failure_rate_limited");
            json(response, 429, "{\"error\":\"RATE_LIMITED\"}");
            return false;
        }
        if (!config.validBearer(request.getHeader("Authorization"))) {
            recordAuthFailure(request.getRemoteAddr(), config);
            audit(request, "API_DENIED", "-", "UNAUTHORIZED", "bearer_invalid");
            json(response, 401, "{\"error\":\"UNAUTHORIZED\"}");
            return false;
        }
        if (config.requireProtectedSequenceStorage()
                && "plaintext".equals(GraphicalMatrixSequenceStorage.load(GraphicalMatrixRuntime.idpHome()).mode())) {
            audit(request, "API_DENIED", "-", "CONFIG_ERROR", "sequence_storage_plaintext");
            json(response, 503, "{\"error\":\"API_SEQUENCE_STORAGE_NOT_PROTECTED\"}");
            return false;
        }
        clearAuthFailures(request.getRemoteAddr());
        return true;
    }

    private static boolean authRateLimited(final String remoteAddress, final GraphicalMatrixApiConfig config) {
        if (!config.rateLimitEnabled()) {
            return false;
        }
        final RateLimitState state = AUTH_FAILURES.get(remoteAddress);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.lockedUntilMillis > System.currentTimeMillis();
        }
    }

    private static void recordAuthFailure(final String remoteAddress, final GraphicalMatrixApiConfig config) {
        if (!config.rateLimitEnabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final RateLimitState state = AUTH_FAILURES.computeIfAbsent(remoteAddress, ignored -> new RateLimitState());
        synchronized (state) {
            while (!state.failures.isEmpty()
                    && now - state.failures.peekFirst().longValue() > config.authFailureWindowMillis()) {
                state.failures.removeFirst();
            }
            state.failures.addLast(Long.valueOf(now));
            if (state.failures.size() >= config.authFailureLimit()) {
                state.lockedUntilMillis = now + config.authFailureLockMillis();
                state.failures.clear();
            }
        }
    }

    private static void clearAuthFailures(final String remoteAddress) {
        AUTH_FAILURES.remove(remoteAddress);
    }

    private static void health(final HttpServletResponse response) throws Exception {
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1");
            }
        }
        json(response, 200, "{\"status\":\"OK\"}");
    }

    private static void graphicals(final HttpServletResponse response, final GraphicalMatrixConfig config)
            throws IOException {
        final StringBuilder out = new StringBuilder();
        out.append("{\"columns\":").append(config.getColumns())
            .append(",\"rows\":").append(config.getRows())
            .append(",\"choice\":").append(config.getChoiceCount())
            .append(",\"order\":").append(config.getOrderMode())
            .append(",\"allowDuplicates\":").append(config.isDuplicateSelectionsAllowed())
            .append(",\"graphicals\":").append(jsonArray(config.getGraphicalIds()))
            .append(",\"aliases\":{");
        boolean first = true;
        for (final java.util.Map.Entry<String, String> entry : config.getAliases().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(json(entry.getKey())).append("\":\"")
                .append(json(entry.getValue())).append('"');
        }
        out.append("}}");
        json(response, 200, out.toString());
    }

    private static void users(final HttpServletRequest request, final HttpServletResponse response,
            final String method, final List<String> path, final GraphicalMatrixConfig config) throws Exception {
        final String user = path.get(1);
        if (!validUser(user)) {
            throw new ApiException(400, "BAD_USER_ID", "API_BAD_REQUEST", user, "BAD_REQUEST", "invalid_user_id");
        }

        if (path.size() == 2 && "GET".equals(method)) {
            final DbUser row = findUser(user);
            if (row == null) {
                throw new ApiException(404, "USER_NOT_FOUND", "API_USER_READ", user, "NOT_FOUND", "missing_user");
            }
            json(response, 200, row.toJson());
            return;
        }
        if (path.size() == 2 && "PUT".equals(method)) {
            final JsonBody body = JsonBody.read(request);
            final DbUser row = upsertUser(user, body, config);
            audit(request, "API_USER_UPDATED", user, "OK",
                "mfa_method=" + row.mfaMethod + ",sequence_length=" + GraphicalMatrixSupport.csv(row.sequence).size());
            json(response, 200, row.toJson());
            return;
        }
        if (path.size() == 2 && "DELETE".equals(method)) {
            final boolean deleted = deleteUser(user);
            if (!deleted) {
                throw new ApiException(404, "USER_NOT_FOUND", "API_USER_DELETED", user, "NOT_FOUND", "missing_user");
            }
            audit(request, "API_USER_DELETED", user, "OK", "deleted");
            json(response, 200, "{\"userId\":\"" + json(user) + "\",\"deleted\":true}");
            return;
        }
        if (path.size() == 3 && "PATCH".equals(method) && "method".equals(path.get(2))) {
            final String mfaMethod = normalizeMethod(JsonBody.read(request).string("mfaMethod"));
            final boolean updated = setMethod(user, mfaMethod);
            if (!updated) {
                throw new ApiException(404, "USER_NOT_FOUND", "API_METHOD_CHANGED", user, "NOT_FOUND", "missing_user");
            }
            audit(request, "API_METHOD_CHANGED", user, "OK", "mfa_method=" + mfaMethod);
            json(response, 200, findUser(user).toJson());
            return;
        }
        if (path.size() == 3 && "POST".equals(method)) {
            final String action = path.get(2);
            if ("reset".equals(action)) {
                actionResult(request, response, user, resetUser(user, config), "API_USER_RESET", "reset");
                return;
            }
            if ("unlock".equals(action)) {
                actionResult(request, response, user, updateSimple(user,
                    "UPDATE graphicalmatrix_enrollment SET failed_count = 0, locked_until = 0, "
                    + "state_version = state_version + 1, updated_at = ? WHERE user_id = ?"),
                    "API_UNLOCKED", "unlocked");
                return;
            }
            if ("enable".equals(action)) {
                actionResult(request, response, user, updateSimple(user,
                    "UPDATE graphicalmatrix_enrollment SET status = 'ACTIVE', "
                    + "state_version = state_version + 1, updated_at = ? WHERE user_id = ?"),
                    "API_ENABLED", "enabled");
                return;
            }
            if ("disable".equals(action)) {
                actionResult(request, response, user, updateSimple(user,
                    "UPDATE graphicalmatrix_enrollment SET status = 'DISABLED', "
                    + "state_version = state_version + 1, updated_at = ? WHERE user_id = ?"),
                    "API_DISABLED", "disabled");
                return;
            }
            if ("totp-reset".equals(action)) {
                actionResult(request, response, user, updateSimple(user,
                    "UPDATE graphicalmatrix_enrollment SET totp_seed = NULL, totp_status = 'UNREGISTERED', "
                    + "totp_registered_at = 0, state_version = state_version + 1, "
                    + "updated_at = ? WHERE user_id = ?"),
                    "API_TOTP_RESET", "totp_reset");
                return;
            }
        }
        json(response, 404, "{\"error\":\"NOT_FOUND\"}");
    }

    private static void actionResult(final HttpServletRequest request, final HttpServletResponse response,
            final String user, final boolean updated, final String event, final String detail) throws Exception {
        if (!updated) {
            throw new ApiException(404, "USER_NOT_FOUND", event, user, "NOT_FOUND", "missing_user");
        }
        audit(request, event, user, "OK", detail);
        json(response, 200, findUser(user).toJson());
    }

    private static DbUser upsertUser(final String user, final JsonBody body,
            final GraphicalMatrixConfig config) throws Exception {
        final DbUser existing = findUser(user);
        final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(GraphicalMatrixRuntime.idpHome());
        final long now = System.currentTimeMillis();
        final String method = normalizeMethod(defaultString(body.string("mfaMethod"),
            existing != null ? existing.mfaMethod : "GraphicalMatrix"));
        final String status = normalizeStatus(defaultString(body.string("status"),
            existing != null ? existing.status : "ACTIVE"));
        final Integer force = body.boolAsInt("forceSequenceChange");
        final int forceValue = force != null ? force.intValue()
            : (existing != null ? existing.forceSequenceChange : 0);

        final List<String> initialTokens = body.sequence("initialSequence");
        final List<String> sequenceTokens = body.sequence("sequence");
        String storedInitialSequence = existing != null ? existing.initialSequence : "";
        String storedSequence = existing != null ? existing.sequence : "";
        String plainSequence = existing != null
            ? String.join(",", storage.displayTokens(existing.sequence))
            : "";

        if (!initialTokens.isEmpty()) {
            final List<String> initial = config.resolveSequenceToGraphicals(initialTokens);
            config.validateSequence(initial);
            storedInitialSequence = String.join(",", config.normalizeInitialSequence(initial));
        }
        if (!sequenceTokens.isEmpty()) {
            plainSequence = String.join(",", config.resolveSequenceToGraphicals(sequenceTokens));
        } else if (!initialTokens.isEmpty()) {
            plainSequence = String.join(",", config.resolveSequenceToGraphicals(initialTokens));
        }
        if (plainSequence.isEmpty() && storedSequence.isEmpty()) {
            throw new ApiException(400, "SEQUENCE_REQUIRED", "API_BAD_REQUEST", user, "BAD_REQUEST", "sequence_required");
        }
        if (!plainSequence.isEmpty()) {
            config.validateSequence(GraphicalMatrixSupport.csv(plainSequence));
            storedSequence = storage.encode(GraphicalMatrixSupport.csv(plainSequence),
                config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed());
        }
        if (storedInitialSequence.isEmpty()) {
            storedInitialSequence = String.join(",",
                config.normalizeInitialSequence(GraphicalMatrixSupport.csv(plainSequence)));
        }

        try (Connection c = db()) {
            initDbIfEnabled(c);
            if (existing == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO graphicalmatrix_enrollment "
                        + "(user_id, mfa_method, force_sequence_change, initial_sequence, sequence, status, "
                        + "failed_count, locked_until, totp_seed, totp_status, totp_registered_at, "
                        + "last_success_at, state_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NULL, 'UNREGISTERED', 0, 0, 0, ?, ?)")) {
                    ps.setString(1, user);
                    ps.setString(2, method);
                    ps.setInt(3, forceValue);
                    ps.setString(4, storedInitialSequence);
                    ps.setString(5, storedSequence);
                    ps.setString(6, status);
                    ps.setLong(7, now);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE graphicalmatrix_enrollment "
                        + "SET mfa_method = ?, force_sequence_change = ?, initial_sequence = ?, "
                        + "sequence = ?, status = ?, state_version = state_version + 1, updated_at = ? "
                        + "WHERE user_id = ?")) {
                    ps.setString(1, method);
                    ps.setInt(2, forceValue);
                    ps.setString(3, storedInitialSequence);
                    ps.setString(4, storedSequence);
                    ps.setString(5, status);
                    ps.setLong(6, now);
                    ps.setString(7, user);
                    ps.executeUpdate();
                }
            }
        }
        return findUser(user);
    }

    private static boolean setMethod(final String user, final String method) throws Exception {
        final long now = System.currentTimeMillis();
        final String sql;
        if ("TOTP".equals(method)) {
            sql = "UPDATE graphicalmatrix_enrollment SET mfa_method = 'TOTP', totp_seed = NULL, "
                + "totp_status = 'UNREGISTERED', totp_registered_at = 0, failed_count = 0, "
                + "locked_until = 0, state_version = state_version + 1, updated_at = ? WHERE user_id = ?";
        } else if ("WebAuthn".equals(method)) {
            sql = "UPDATE graphicalmatrix_enrollment SET mfa_method = 'WebAuthn', failed_count = 0, "
                + "locked_until = 0, state_version = state_version + 1, updated_at = ? WHERE user_id = ?";
        } else {
            sql = "UPDATE graphicalmatrix_enrollment SET mfa_method = 'GraphicalMatrix', failed_count = 0, "
                + "locked_until = 0, state_version = state_version + 1, updated_at = ? WHERE user_id = ?";
        }
        return updateSimple(user, sql, now);
    }

    private static boolean resetUser(final String user, final GraphicalMatrixConfig config) throws Exception {
        final DbUser row = findUser(user);
        if (row == null) {
            return false;
        }
        final GraphicalMatrixSequenceStorage storage =
            GraphicalMatrixSequenceStorage.load(GraphicalMatrixRuntime.idpHome());
        if (row.initialSequence == null || row.initialSequence.trim().isEmpty()) {
            return false;
        }
        final List<String> initial = config.resolveSequenceToGraphicals(
            GraphicalMatrixSupport.csv(row.initialSequence));
        config.validateSequence(initial);
        final String resetSequence = storage.encode(initial,
            config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed());
        final long now = System.currentTimeMillis();
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE graphicalmatrix_enrollment "
                    + "SET sequence = ?, mfa_method = 'GraphicalMatrix', "
                    + "status = 'ACTIVE', "
                    + "failed_count = 0, locked_until = 0, totp_seed = NULL, "
                    + "totp_status = 'UNREGISTERED', totp_registered_at = 0, "
                    + "force_sequence_change = 1, state_version = state_version + 1, "
                    + "updated_at = ? WHERE user_id = ?")) {
                ps.setString(1, resetSequence);
                ps.setLong(2, now);
                ps.setString(3, user);
                return ps.executeUpdate() == 1;
            }
        }
    }

    private static boolean deleteUser(final String user) throws Exception {
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
                ps.setString(1, user);
                return ps.executeUpdate() == 1;
            }
        }
    }

    private static boolean updateSimple(final String user, final String sql) throws Exception {
        return updateSimple(user, sql, System.currentTimeMillis());
    }

    private static boolean updateSimple(final String user, final String sql, final long now) throws Exception {
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, now);
                ps.setString(2, user);
                return ps.executeUpdate() == 1;
            }
        }
    }

    private static DbUser findUser(final String user) throws Exception {
        try (Connection c = db()) {
            initDbIfEnabled(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT user_id, mfa_method, force_sequence_change, initial_sequence, sequence, "
                    + "status, failed_count, locked_until, totp_status, "
                    + "CASE WHEN totp_seed IS NULL OR totp_seed = '' THEN 0 ELSE 1 END AS totp_seed_set, "
                    + "totp_registered_at, last_success_at, created_at, updated_at "
                    + "FROM graphicalmatrix_enrollment WHERE user_id = ?")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return DbUser.from(rs);
                }
            }
        }
    }

    private static Connection db() throws Exception {
        return GraphicalMatrixDataSource.getConnection(GraphicalMatrixRuntime.idpHome());
    }

    private static void initDbIfEnabled(final Connection c) throws Exception {
        if (!GraphicalMatrixDbConfig.load(GraphicalMatrixRuntime.idpHome()).isAutoInit()) {
            return;
        }
        if (SCHEMA_INITIALIZED.get()) {
            return;
        }
        if (SCHEMA_INITIALIZED.compareAndSet(false, true)) {
            try {
                initDb(c);
            } catch (Exception ex) {
                SCHEMA_INITIALIZED.set(false);
                throw ex;
            }
        }
    }

    private static void initDb(final Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS graphicalmatrix_enrollment ("
                + "user_id VARCHAR(255) PRIMARY KEY, sequence VARCHAR(1024) NOT NULL, "
                + "initial_sequence VARCHAR(1024) NOT NULL DEFAULT '', "
                + "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', failed_count INT NOT NULL DEFAULT 0, "
                + "locked_until BIGINT NOT NULL DEFAULT 0, mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix', "
                + "totp_seed VARCHAR(255), totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED', "
                    + "totp_registered_at BIGINT NOT NULL DEFAULT 0, last_success_at BIGINT NOT NULL DEFAULT 0, "
                    + "force_sequence_change INT NOT NULL DEFAULT 0, state_version BIGINT NOT NULL DEFAULT 0, "
                    + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)"
            );
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS initial_sequence VARCHAR(1024) NOT NULL DEFAULT ''");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix'");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS force_sequence_change INT NOT NULL DEFAULT 0");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS totp_seed VARCHAR(255)");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED'");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS totp_registered_at BIGINT NOT NULL DEFAULT 0");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS last_success_at BIGINT NOT NULL DEFAULT 0");
            st.executeUpdate("ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0");
        }
    }

    private static List<String> path(final HttpServletRequest request) {
        final List<String> out = new ArrayList<>();
        final String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.trim().isEmpty()) {
            return out;
        }
        for (final String raw : pathInfo.split("/")) {
            if (!raw.isEmpty()) {
                out.add(URLDecoder.decode(raw, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static boolean validUser(final String user) {
        return user != null && USER_ID.matcher(user).matches();
    }

    private static String normalizeMethod(final String method) {
        String value = method != null ? method.trim() : "";
        if (value.regionMatches(true, 0, "MFA:", 0, 4)) {
            value = value.substring(4).trim();
        }
        final String upper = value.toUpperCase(Locale.ROOT);
        if ("GRAPHICALMATRIX".equals(upper)) {
            return "GraphicalMatrix";
        }
        if ("TOTP".equals(upper)) {
            return "TOTP";
        }
        if ("WEBAUTHN".equals(upper)) {
            return "WebAuthn";
        }
        throw new ApiException(400, "UNSUPPORTED_MFA_METHOD", "API_BAD_REQUEST", "-", "BAD_REQUEST", "mfa_method=" + value);
    }

    private static String normalizeStatus(final String status) {
        final String upper = status != null ? status.trim().toUpperCase(Locale.ROOT) : "";
        if ("ACTIVE".equals(upper) || "DISABLED".equals(upper)) {
            return upper;
        }
        throw new ApiException(400, "UNSUPPORTED_STATUS", "API_BAD_REQUEST", "-", "BAD_REQUEST", "status=" + status);
    }

    private static String defaultString(final String value, final String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void noStore(final HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }

    private static void json(final HttpServletResponse response, final int status, final String body)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write(body);
    }

    private static String json(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String jsonArray(final List<String> values) {
        final StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(json(values.get(i))).append('"');
        }
        return out.append(']').toString();
    }

    private static void audit(final HttpServletRequest request, final String event,
            final String user, final String result, final String detail) {
        GraphicalMatrixRuntime.auditLogger().log(event, user, result, null, detail, request);
    }

    private static final class DbUser {
        private String userId;
        private String mfaMethod;
        private int forceSequenceChange;
        private String initialSequence;
        private String sequence;
        private String status;
        private int failedCount;
        private long lockedUntil;
        private String totpStatus;
        private boolean totpSeedSet;
        private long totpRegisteredAt;
        private long lastSuccessAt;
        private long createdAt;
        private long updatedAt;

        static DbUser from(final ResultSet rs) throws Exception {
            final DbUser out = new DbUser();
            out.userId = rs.getString("user_id");
            out.mfaMethod = rs.getString("mfa_method");
            out.forceSequenceChange = rs.getInt("force_sequence_change");
            out.initialSequence = rs.getString("initial_sequence");
            out.sequence = rs.getString("sequence");
            out.status = rs.getString("status");
            out.failedCount = rs.getInt("failed_count");
            out.lockedUntil = rs.getLong("locked_until");
            out.totpStatus = rs.getString("totp_status");
            out.totpSeedSet = rs.getInt("totp_seed_set") != 0;
            out.totpRegisteredAt = rs.getLong("totp_registered_at");
            out.lastSuccessAt = rs.getLong("last_success_at");
            out.createdAt = rs.getLong("created_at");
            out.updatedAt = rs.getLong("updated_at");
            return out;
        }

        String toJson() {
            final GraphicalMatrixApiConfig apiConfig = GraphicalMatrixApiConfig.load(GraphicalMatrixRuntime.idpHome());
            final GraphicalMatrixSequenceStorage storage =
                GraphicalMatrixSequenceStorage.load(GraphicalMatrixRuntime.idpHome());
            final boolean sequencesExcluded = apiConfig.excludeSequences();
            final java.util.List<String> displayInitialSequence = sequencesExcluded
                ? new ArrayList<>() : displayInitialSequence();
            final java.util.List<String> displaySequence = sequencesExcluded
                ? new ArrayList<>() : storage.displayTokens(sequence);
            return "{\"userId\":\"" + json(userId)
                + "\",\"mfaMethod\":\"" + json(mfaMethod)
                + "\",\"forceSequenceChange\":" + (forceSequenceChange != 0)
                + ",\"initialSequence\":" + jsonArray(displayInitialSequence)
                + ",\"sequence\":" + jsonArray(displaySequence)
                + ",\"sequenceStorage\":\"" + json(storage.storedMode(sequence))
                + "\",\"sequenceRecoverable\":" + storage.recoverable(sequence)
                + ",\"sequencesExcluded\":" + sequencesExcluded
                + ",\"status\":\"" + json(status)
                + "\",\"failedCount\":" + failedCount
                + ",\"lockedUntil\":" + lockedUntil
                + ",\"totpStatus\":\"" + json(totpStatus)
                + "\",\"totpSeedSet\":" + totpSeedSet
                + ",\"totpRegisteredAt\":" + totpRegisteredAt
                + ",\"lastSuccessAt\":" + lastSuccessAt
                + ",\"createdAt\":" + createdAt
                + ",\"updatedAt\":" + updatedAt
                + "}";
        }

        private java.util.List<String> displayInitialSequence() {
            try {
                return GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome())
                    .normalizeInitialSequence(GraphicalMatrixSupport.csv(initialSequence));
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to display initial sequence", ex);
            }
        }
    }

    private static final class JsonBody {
        private final String raw;

        private JsonBody(final String raw) {
            this.raw = raw != null ? raw : "";
        }

        static JsonBody read(final HttpServletRequest request) throws IOException {
            return new JsonBody(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }

        String string(final String name) {
            final Matcher matcher = Pattern.compile(String.format(JSON_STRING.pattern(), Pattern.quote(name))).matcher(raw);
            return matcher.find() ? unescape(matcher.group(1)) : null;
        }

        Integer boolAsInt(final String name) {
            final Matcher matcher = Pattern.compile(String.format(JSON_BOOLEAN.pattern(), Pattern.quote(name)),
                Pattern.CASE_INSENSITIVE).matcher(raw);
            if (!matcher.find()) {
                return null;
            }
            String value = matcher.group(1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            final String normalized = value.trim().toLowerCase(Locale.ROOT);
            return ("true".equals(normalized) || "1".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized)) ? 1 : 0;
        }

        List<String> sequence(final String name) {
            final Matcher array = Pattern.compile(String.format(JSON_ARRAY.pattern(), Pattern.quote(name)),
                Pattern.DOTALL).matcher(raw);
            if (array.find()) {
                final List<String> out = new ArrayList<>();
                final Matcher item = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(array.group(1));
                while (item.find()) {
                    final String value = unescape(item.group(1)).trim();
                    if (!value.isEmpty()) {
                        out.add(value);
                    }
                }
                return out;
            }
            final String asString = string(name);
            return asString != null ? GraphicalMatrixSupport.csv(asString) : new ArrayList<>();
        }

        private static String unescape(final String value) {
            final StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                if (escaped) {
                    if (c == 'n') {
                        out.append('\n');
                    } else if (c == 'r') {
                        out.append('\r');
                    } else if (c == 't') {
                        out.append('\t');
                    } else {
                        out.append(c);
                    }
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    private static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;
        private final String event;
        private final String user;
        private final String result;
        private final String detail;

        ApiException(final int status, final String code, final String event,
                final String user, final String result, final String detail) {
            this.status = status;
            this.code = code;
            this.event = event;
            this.user = user;
            this.result = result;
            this.detail = detail;
        }
    }

    private static final class RateLimitState {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long lockedUntilMillis;
    }
}
