package io.github.yasakawa.faskw;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class GraphicalMatrixSaveDataConfig {
    private final String mode;

    private GraphicalMatrixSaveDataConfig(final String mode) {
        this.mode = mode;
    }

    public static GraphicalMatrixSaveDataConfig load(final String idpHome) {
        final Properties props = new Properties();
        final Path path = propertiesPath(idpHome);
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to load GraphicalMatrix properties: " + path, ex);
            }
        }
        final String mode = normalize(props.getProperty("graphicalmatrix.savedata", "db"));
        return new GraphicalMatrixSaveDataConfig(mode);
    }

    public String mode() {
        return mode;
    }

    public boolean isLdap() {
        return "ldap".equals(mode);
    }

    static String normalize(final String raw) {
        final String value = raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
        if (value.isEmpty() || "db".equals(value) || "database".equals(value)) {
            return "db";
        }
        if ("ldap".equals(value)) {
            return "ldap";
        }
        throw new IllegalArgumentException("Unsupported graphicalmatrix.savedata: " + raw);
    }

    private static Path propertiesPath(final String idpHome) {
        final String override = System.getenv("GRAPHICAL_PROPERTIES") != null
            ? System.getenv("GRAPHICAL_PROPERTIES").trim()
            : "";
        return override.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "graphicalmatrix.properties")
            : Path.of(override);
    }
}
