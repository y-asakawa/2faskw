package io.github.yasakawa.faskw.graphicalmatrix;

public final class GraphicalMatrixMfaSettings {
    private final String method;
    private final String totpStatus;
    private final boolean totpSeedSet;

    public GraphicalMatrixMfaSettings(final String method, final String totpStatus, final boolean totpSeedSet) {
        this.method = method;
        this.totpStatus = totpStatus;
        this.totpSeedSet = totpSeedSet;
    }

    public String getMethod() {
        return method;
    }

    public String getTotpStatus() {
        return totpStatus;
    }

    public boolean isTotpSeedSet() {
        return totpSeedSet;
    }

    public boolean isTotpActive() {
        return "ACTIVE".equalsIgnoreCase(totpStatus) && totpSeedSet;
    }
}
