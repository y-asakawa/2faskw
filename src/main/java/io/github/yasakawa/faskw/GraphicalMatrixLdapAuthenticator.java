package io.github.yasakawa.faskw;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

public final class GraphicalMatrixLdapAuthenticator {
    private static final Pattern PLACEHOLDER = Pattern.compile("%\\{([^}:]+)(?::([^}]*))?}");

    private final Properties properties;

    public GraphicalMatrixLdapAuthenticator(final String idpHome) throws IOException {
        properties = new Properties();
        load(Path.of(idpHome, "conf", "ldap.properties"));
        load(Path.of(idpHome, "credentials", "secrets.properties"));
        properties.setProperty("idp.home", idpHome);
    }

    public boolean authenticate(final String user, final String password) throws NamingException {
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            return false;
        }

        final String authenticator = property("idp.authn.LDAP.authenticator", "bindSearchAuthenticator");
        if ("directAuthenticator".equals(authenticator) || "adAuthenticator".equals(authenticator)) {
            return authenticateDirect(user, password);
        }

        return authenticateBySearch(user, password, "bindSearchAuthenticator".equals(authenticator));
    }

    private boolean authenticateBySearch(final String user, final String password,
            final boolean serviceBind) throws NamingException {
        final String ldapUrl = property("idp.authn.LDAP.ldapURL", "");
        final String baseDn = property("idp.authn.LDAP.baseDN", "");
        final String userFilter = property("idp.authn.LDAP.userFilter", "(uid={user})");
        if (ldapUrl.isBlank() || baseDn.isBlank()) {
            return false;
        }

        LdapContext serviceContext = null;
        try {
            if (serviceBind) {
                serviceContext = context(ldapUrl,
                    property("idp.authn.LDAP.bindDN", ""),
                    property("idp.authn.LDAP.bindDNCredential", ""));
            } else {
                serviceContext = context(ldapUrl, "", "");
            }

            final SearchControls controls = new SearchControls();
            controls.setSearchScope(booleanProperty("idp.authn.LDAP.subtreeSearch", false)
                ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
            controls.setReturningAttributes(new String[] {"dn"});
            controls.setCountLimit(2L);

            final String filter = userFilter.replace("{user}", escapeFilter(user));
            final NamingEnumeration<SearchResult> results =
                serviceContext.search(baseDn, filter, controls);
            if (!results.hasMore()) {
                return false;
            }
            final String userDn = results.next().getNameInNamespace();
            if (results.hasMore()) {
                return false;
            }

            return bindUser(ldapUrl, userDn, password);
        } finally {
            close(serviceContext);
        }
    }

    private boolean authenticateDirect(final String user, final String password) throws NamingException {
        final String ldapUrl = property("idp.authn.LDAP.ldapURL", "");
        final String dnFormat = property("idp.authn.LDAP.dnFormat", "");
        if (ldapUrl.isBlank() || dnFormat.isBlank()) {
            return false;
        }
        return bindUser(ldapUrl, String.format(dnFormat, escapeDnValue(user)), password);
    }

    private boolean bindUser(final String ldapUrl, final String userDn, final String password)
            throws NamingException {
        LdapContext userContext = null;
        try {
            userContext = context(ldapUrl, userDn, password);
            return true;
        } catch (NamingException ex) {
            return false;
        } finally {
            close(userContext);
        }
    }

    private LdapContext context(final String ldapUrl, final String principal, final String credential)
            throws NamingException {
        final Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapUrl);
        env.put("com.sun.jndi.ldap.connect.timeout",
            String.valueOf(durationMillis(property("idp.authn.LDAP.connectTimeout", "PT3S"))));
        env.put("com.sun.jndi.ldap.read.timeout",
            String.valueOf(durationMillis(property("idp.authn.LDAP.responseTimeout", "PT3S"))));

        if (principal != null && !principal.isBlank()) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, principal);
            env.put(Context.SECURITY_CREDENTIALS, credential != null ? credential : "");
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        }

        if (booleanProperty("idp.authn.LDAP.useStartTLS", false)) {
            throw new NamingException("StartTLS is not supported by GraphicalMatrix self-service LDAP login yet.");
        }
        return new InitialLdapContext(env, null);
    }

    private void load(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
    }

    private String property(final String key, final String defaultValue) {
        return resolve(properties.getProperty(key, defaultValue), 0);
    }

    private String resolve(final String value, final int depth) {
        if (value == null || depth > 8) {
            return value;
        }

        final Matcher matcher = PLACEHOLDER.matcher(value);
        final StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            final String replacement = properties.getProperty(matcher.group(1), matcher.group(2) != null
                ? matcher.group(2) : "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolve(replacement, depth + 1)));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }

    private boolean booleanProperty(final String key, final boolean defaultValue) {
        return Boolean.parseBoolean(property(key, String.valueOf(defaultValue)));
    }

    private long durationMillis(final String value) {
        if (value == null || value.isBlank()) {
            return 3000L;
        }
        final String trimmed = value.trim();
        if (trimmed.startsWith("PT") && trimmed.endsWith("S")) {
            try {
                return Math.max(1000L, Long.parseLong(trimmed.substring(2, trimmed.length() - 1)) * 1000L);
            } catch (NumberFormatException ex) {
                return 3000L;
            }
        }
        try {
            return Math.max(1000L, Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            return 3000L;
        }
    }

    private static String escapeFilter(final String value) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\5c");
            } else if (c == '*') {
                out.append("\\2a");
            } else if (c == '(') {
                out.append("\\28");
            } else if (c == ')') {
                out.append("\\29");
            } else if (c == '\u0000') {
                out.append("\\00");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeDnValue(final String value) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean leading = i == 0;
            final boolean trailing = i == value.length() - 1;
            if (c == '\u0000') {
                out.append("\\00");
            } else if (c < 0x20) {
                out.append('\\').append(String.format("%02X", (int) c));
            } else if ((leading && (c == ' ' || c == '#')) || (trailing && c == ' ')
                    || c == '"' || c == '+' || c == ',' || c == ';' || c == '<'
                    || c == '>' || c == '\\' || c == '=') {
                out.append('\\').append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void close(final LdapContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (NamingException ex) {
            // Nothing useful can be done here.
        }
    }
}
