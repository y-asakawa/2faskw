# Common MFA Completion Recording

This document follows the implementation, not a fixed release number. Update it with changes
to completion validation, storage semantics, the audit wire format or Dashboard indexing.

## Boundary And Call Graph

```text
Password -> decision/guard -> selected second factor -> completion strategy
  -> mandatory subject and enrollment/version validation
  -> optional conditional timestamp update on the same storage connection
  -> existing self-service receipt, when requested
  -> optional MFA_COMPLETE/OK audit
```

GraphicalMatrix, TOTP and WebAuthn share this completion point. Bypass, standalone image checks,
registration-only operations and plugin flows not routed here are outside its observation.
Completion is not SP authorization or proof of a newly exercised factor. Later authorization
denial or rejected self-service handoff does not erase a previously accepted MFA completion.

## Responsibilities

| Component | Responsibility |
| --- | --- |
| `GraphicalMatrixMfaSuccessRecordOptions` | Strict independent storage/audit booleans and a 1–5 second SQL write timeout. |
| `GraphicalMatrixMfaSuccessRecord` | Separate mandatory state verdict from best-effort storage result; monotonic time, one update, at most one conflict reread. |
| `GraphicalMatrixRepository` | Reuse a connection for state SELECT and conditional autocommit timestamp UPDATE; retain registration and lock semantics. |
| `GraphicalMatrixLdapEnrollmentStore` | Reuse one LDAP session; critical assertions include raw version/state/readiness and timestamp equality/absence. No unconditional fallback. |
| `GraphicalMatrixMfaCompletionRecordContext` | Per-request write/audit attempt suppression, bound to guard and exact result references. Never authorization evidence. |
| `GraphicalMatrixMfaCompletionStrategy` | Preserve subject binding, fresh state checks and existing self-service receipt; warn on optional failures. |
| `GraphicalMatrixMfaCompletionAudit` / `GraphicalMatrixAuditLogger` | Versioned detail and one shared completion timestamp; retain existing log escaping. |
| Dashboard `MfaCompletionInfo` | Validate/decode bounded wire format; discard source Requester before forwarding. |
| Dashboard parser, normalizer, policy, store | Recognize authentication category, retain privacy/roles, migrate index schema 6, keep VERIFY and completion counters separate. |

## State And Failure Rules

Identity extraction is flow-specific. Password and GraphicalMatrix keep the unique
`UsernamePrincipal` check. For `authn/TOTP`, accept one official `TOTPPrincipal` or the existing
custom-validator `UsernamePrincipal` form. If both types occur, they must agree, and neither
type may be ambiguous. The result's flow ID must match the guarded second factor. Never infer
the TOTP user from a mutable canonicalization/subject context or the Password result alone.
The official TOTP 2.3.1/2.3.2 validator emits `TOTPPrincipal` without `UsernamePrincipal`;
tests must cover this producer shape rather than only synthetic username-based results.

For `authn/WebAuthn`, keep unique `UsernamePrincipal` support. Official WebAuthn 1.4.2
second-factor mode can omit this principal, but its successful result builder creates a
`SubjectCanonicalizationContext` with the result's Subject and the verified canonical name.
Accept this name only when the Subject is the exact same object as the selected WebAuthn
result's Subject, the result is not previous, canonicalization has no exception and the name
is nonempty. If a UsernamePrincipal is present, both identities must agree; multiple username
principals reject. An unrelated/equal-but-distinct Subject or Password/SubjectContext name
alone is not evidence. Never interpret the WebAuthn user handle as the login name. Keep the
Password/guard/name, result-flow and current enrollment/version checks. The official plugin,
its configuration and stored credentials are unchanged. Official WebAuthn API/implementation
and Yubico classes are test-scope dependencies only, excluded from runtime distributions.

Missing, inactive, mismatched-method or mismatched-version enrollments reject authentication.
Failure of the first mandatory lookup also rejects. OFF still performs this lookup. Timestamp
parsing, permissions, optional update/close failures and audit failures do not turn valid MFA
into denial. A conflict reread that observes administrative reset rejects; a newer time is
retained; remaining metadata contention is skipped without retrying the write.

Timestamp storage never changes credentials, lock counters or the registration generation.
It advances `last_success_at`, and `updated_at` only when necessary. Existing future values
are retained and warned about, not corrected. The application does not synchronize OS clocks.
JDBC query timeout covers the optional UPDATE only; LDAP uses its configured network timeouts.

On re-evaluation, the record context never replaces a fresh enrollment check. The existing
self-service receipt still binds the same guard generation and Password/second-factor result
references. Handoff one-time consumption and conditional form saves remain separate checks.

## Audit And Index Contract

`MFA_COMPLETE/OK` detail is `mfa1:` plus canonical basic Base64 of UTF-8 form-encoded fields:
`method,flow,scope,sp,completedAt,store,eventId,stateVersion`. Basic Base64 avoids underscore
damage by the historical outer log decoder. The limit is 16 KiB. Decode outer escaping once,
then Base64, strict UTF-8 and form fields. Reject unknown, duplicate, missing and malformed
fields, method/flow mismatch and disagreement with the outer timestamp. Base64 is not encryption.

The normalized optional `mfaCompletion` object excludes `sp`. Existing user/IP privacy filtering
still applies. Existing event identity remains node/generation/offset/line digest; the random
completion UUID is a correlation value, not a replacement database primary key.

Dashboard schema 6 adds seven nullable completion columns. Old events with missing metadata
remain visible but are not guessed into method counts. `MFA_COMPLETE` is AUTHENTICATION even
for self-service scope. Old VERIFY statistics and management-event role restrictions remain.
Method/scope/store breakdowns query retained individual events, not historical hourly totals;
their retention follows `dashboard.storage.retentionDays`, not `aggregateRetentionDays`.
Back up the stopped Dashboard database before migration; older Dashboard binaries reject
schema 6. Reimport original audit logs to populate metadata lost by old Agent normalization.

## Performance And Verification

Normally one mandatory state read is reused, with at most one additional conditional write per
accepted completion. GraphicalMatrix's pre-existing counter write still occurs. OFF does not
remove authentication reads or registration writes. Measure peak throughput, same-user
contention, latency percentiles and audit volume; no production throughput guarantee is made.

Tests: `GraphicalMatrixMfaSuccessRecordTest`, `GraphicalMatrixMfaCompletionIntegrationTest`,
`GraphicalMatrixMfaLdapCompletionTest`, `GraphicalMatrixMfaCompletionAuditTest`, and Dashboard
`MfaCompletionTest`. The shared `test-fixtures/mfa-completion-v1.txt` fixes the producer/consumer
wire contract. H2 and simulated LDAP tests do not certify real PostgreSQL/LDAP performance,
LDAP server assertion support, plugin/browser TOTP/WebAuthn behavior or hardware authenticators.
