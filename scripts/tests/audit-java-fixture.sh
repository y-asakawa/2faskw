#!/usr/bin/env bash
# Test-only bridge to a non-root audit sink. Never shipped in a runtime package.
set -euo pipefail
[[ "${1:-}" == -cp ]] || exec "$GM_REAL_JAVA" "$@"
cp="$2:$GM_AUDIT_RUNTIME_CP"
shift 2
if [[ "${1:-}" == io.github.yasakawa.faskw.GraphicalMatrixAdminAuditTool && "${2:-}" == run ]]; then
  shift 2
  exec "$GM_REAL_JAVA" -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixAdminAuditFixture "$@"
fi
exec "$GM_REAL_JAVA" -cp "$cp" "$@"
