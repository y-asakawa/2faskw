package io.github.yasakawa.faskw.graphicalmatrix;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class GraphicalMatrixDbConfig {
    private final String driver;
    private final String url;
    private final String user;
    private final String password;
    private final boolean autoInit;
    private final boolean poolEnabled;
    private final int poolMaximumSize;
    private final int poolMinimumIdle;
    private final long poolConnectionTimeoutMillis;
    private final long poolIdleTimeoutMillis;
    private final long poolMaxLifetimeMillis;
    private final long poolValidationTimeoutMillis;

    private GraphicalMatrixDbConfig(final String driver, final String url,
            final String user, final String password, final boolean autoInit,
            final boolean poolEnabled, final int poolMaximumSize, final int poolMinimumIdle,
            final long poolConnectionTimeoutMillis, final long poolIdleTimeoutMillis,
            final long poolMaxLifetimeMillis, final long poolValidationTimeoutMillis) {
        this.driver = driver;
        this.url = url;
        this.user = user;
        this.password = password;
        this.autoInit = autoInit;
        this.poolEnabled = poolEnabled;
        this.poolMaximumSize = poolMaximumSize;
        this.poolMinimumIdle = poolMinimumIdle;
        this.poolConnectionTimeoutMillis = poolConnectionTimeoutMillis;
        this.poolIdleTimeoutMillis = poolIdleTimeoutMillis;
        this.poolMaxLifetimeMillis = poolMaxLifetimeMillis;
        this.poolValidationTimeoutMillis = poolValidationTimeoutMillis;
    }

    public static GraphicalMatrixDbConfig load(final String idpHome) {
        final Properties props = new Properties();
        final String overridePath = trim(System.getenv("DB_PROPERTIES"));
        final Path propertiesPath = overridePath.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "db.properties")
            : Path.of(overridePath);
        if (Files.isRegularFile(propertiesPath)) {
            try (FileInputStream in = new FileInputStream(propertiesPath.toFile())) {
                props.load(in);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to load DB properties: " + propertiesPath, ex);
            }
        }

        final String driver = trim(props.getProperty("graphicalmatrix.db.driver",
            "org.h2.Driver"));
        final String url = trim(props.getProperty("graphicalmatrix.db.url",
            "jdbc:h2:file:" + idpHome + "/credentials/graphicalmatrix;MODE=PostgreSQL;DATABASE_TO_UPPER=false"));
        final String user = trim(props.getProperty("graphicalmatrix.db.user", "sa"));
        final String password = password(props);
        final boolean autoInit = booleanProperty(props, "graphicalmatrix.db.autoInit", false);
        final boolean poolEnabled = booleanProperty(props, "graphicalmatrix.db.pool.enabled", true);
        final int poolMaximumSize = intProperty(props, "graphicalmatrix.db.pool.maximumPoolSize", 10);
        final int poolMinimumIdle = intProperty(props, "graphicalmatrix.db.pool.minimumIdle", 2);
        final long poolConnectionTimeoutMillis =
            longProperty(props, "graphicalmatrix.db.pool.connectionTimeoutMillis", 30_000L);
        final long poolIdleTimeoutMillis =
            longProperty(props, "graphicalmatrix.db.pool.idleTimeoutMillis", 600_000L);
        final long poolMaxLifetimeMillis =
            longProperty(props, "graphicalmatrix.db.pool.maxLifetimeMillis", 1_800_000L);
        final long poolValidationTimeoutMillis =
            longProperty(props, "graphicalmatrix.db.pool.validationTimeoutMillis", 5_000L);
        return new GraphicalMatrixDbConfig(driver, url, user, password, autoInit,
            poolEnabled, poolMaximumSize, poolMinimumIdle, poolConnectionTimeoutMillis,
            poolIdleTimeoutMillis, poolMaxLifetimeMillis, poolValidationTimeoutMillis);
    }

    public String getDriver() {
        return driver;
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public boolean isAutoInit() {
        return autoInit;
    }

    public boolean isPoolEnabled() {
        return poolEnabled;
    }

    public int getPoolMaximumSize() {
        return poolMaximumSize;
    }

    public int getPoolMinimumIdle() {
        return poolMinimumIdle;
    }

    public long getPoolConnectionTimeoutMillis() {
        return poolConnectionTimeoutMillis;
    }

    public long getPoolIdleTimeoutMillis() {
        return poolIdleTimeoutMillis;
    }

    public long getPoolMaxLifetimeMillis() {
        return poolMaxLifetimeMillis;
    }

    public long getPoolValidationTimeoutMillis() {
        return poolValidationTimeoutMillis;
    }

    public String poolKey() {
        return driver + "\n" + url + "\n" + user + "\n" + Integer.toHexString(password.hashCode()) + "\n"
            + poolEnabled + "\n" + poolMaximumSize + "\n" + poolMinimumIdle + "\n"
            + poolConnectionTimeoutMillis + "\n" + poolIdleTimeoutMillis + "\n"
            + poolMaxLifetimeMillis + "\n" + poolValidationTimeoutMillis;
    }

    private static String password(final Properties props) {
        final String direct = trim(props.getProperty("graphicalmatrix.db.password", ""));
        if (!direct.isEmpty()) {
            return direct;
        }

        final String passwordFile = trim(props.getProperty("graphicalmatrix.db.passwordFile", ""));
        if (passwordFile.isEmpty()) {
            return "";
        }

        try {
            return Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read DB password file: " + passwordFile, ex);
        }
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }

    private static boolean booleanProperty(final Properties props, final String key,
            final boolean defaultValue) {
        final String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        final String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
            || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static int intProperty(final Properties props, final String key, final int defaultValue) {
        final String value = trim(props.getProperty(key, ""));
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long longProperty(final Properties props, final String key, final long defaultValue) {
        final String value = trim(props.getProperty(key, ""));
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
