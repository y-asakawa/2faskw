package io.github.yasakawa.faskw;

public final class GraphicalMatrixRuntime {
    private GraphicalMatrixRuntime() {
    }

    public static String idpHome() {
        return System.getProperty("idp.home", "/opt/shibboleth-idp");
    }

    public static GraphicalMatrixRepository repository() {
        return new GraphicalMatrixRepository(idpHome());
    }

    public static GraphicalMatrixAuditLogger auditLogger() {
        return new GraphicalMatrixAuditLogger(idpHome());
    }
}
