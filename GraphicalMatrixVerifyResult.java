package io.github.yasakawa.faskw.graphicalmatrix;

public final class GraphicalMatrixVerifyResult {
    private final boolean success;
    private final String event;
    private final String auditResult;
    private final String auditDetail;

    private GraphicalMatrixVerifyResult(final boolean success, final String event,
            final String auditResult, final String auditDetail) {
        this.success = success;
        this.event = event;
        this.auditResult = auditResult;
        this.auditDetail = auditDetail;
    }

    public static GraphicalMatrixVerifyResult success() {
        return new GraphicalMatrixVerifyResult(true, null, "OK", "matched");
    }

    public static GraphicalMatrixVerifyResult success(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(true, null, "OK", auditDetail);
    }

    public static GraphicalMatrixVerifyResult failed(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(false, "GraphicalMatrixFailed", "FAIL", auditDetail);
    }

    public static GraphicalMatrixVerifyResult locked(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(false, "GraphicalMatrixLocked", "LOCKED", auditDetail);
    }

    public static GraphicalMatrixVerifyResult enrollRequired(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(false, "GraphicalMatrixEnrollRequired", "ENROLL_REQUIRED", auditDetail);
    }

    public static GraphicalMatrixVerifyResult dbError(final String auditDetail) {
        return new GraphicalMatrixVerifyResult(false, "GraphicalMatrixFailed", "DB_ERROR", auditDetail);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getEvent() {
        return event;
    }

    public String getAuditResult() {
        return auditResult;
    }

    public String getAuditDetail() {
        return auditDetail;
    }
}
