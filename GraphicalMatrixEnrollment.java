package io.github.yasakawa.faskw.graphicalmatrix;

public final class GraphicalMatrixEnrollment {
    private final String sequence;
    private final String status;
    private final int failedCount;
    private final long lockedUntil;
    private final boolean forceSequenceChange;

    public GraphicalMatrixEnrollment(final String sequence, final String status,
            final int failedCount, final long lockedUntil) {
        this(sequence, status, failedCount, lockedUntil, false);
    }

    public GraphicalMatrixEnrollment(final String sequence, final String status,
            final int failedCount, final long lockedUntil, final boolean forceSequenceChange) {
        this.sequence = sequence;
        this.status = status;
        this.failedCount = failedCount;
        this.lockedUntil = lockedUntil;
        this.forceSequenceChange = forceSequenceChange;
    }

    public String getSequence() {
        return sequence;
    }

    public String getStatus() {
        return status;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public long getLockedUntil() {
        return lockedUntil;
    }

    public boolean isForceSequenceChange() {
        return forceSequenceChange;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
