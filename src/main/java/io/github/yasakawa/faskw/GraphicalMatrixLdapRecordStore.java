package io.github.yasakawa.faskw;

import java.io.IOException;
import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import org.opensaml.storage.StorageRecord;
import org.opensaml.storage.VersionMismatchException;

interface GraphicalMatrixLdapRecordStore {
    boolean create(String context, String key, String value, Long expiration) throws IOException;

    StorageRecord<String> read(String context, String key) throws IOException;

    Iterable<String> getContextKeys(String context, String keyPrefix) throws IOException;

    Long update(long version, String context, String key, String value, Long expiration)
        throws IOException, VersionMismatchException;

    boolean delete(long version, String context, String key) throws IOException, VersionMismatchException;

    void reap(String context) throws IOException;

    void updateContextExpiration(String context, Long expiration) throws IOException;

    void deleteContext(String context) throws IOException;

    static LdapSession context(final GraphicalMatrixLdapStorageConfig config) throws NamingException {
        final Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.url());
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(config.connectTimeoutMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(config.responseTimeoutMillis()));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, config.bindDn());
        env.put(Context.SECURITY_CREDENTIALS, config.bindCredential());
        return new LdapSession(new InitialLdapContext(env, null));
    }

    static String escapeFilter(final String value) {
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

    final class LdapSession implements AutoCloseable {
        private final LdapContext context;

        private LdapSession(final LdapContext context) {
            this.context = context;
        }

        LdapContext context() {
            return context;
        }

        @Override
        public void close() throws NamingException {
            context.close();
        }
    }
}
