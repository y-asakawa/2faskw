# Logging and Audit Correlation

This document describes log producers, correlation fields and their implementation boundaries
through v1.4.2. Update it with changes to audit fields, identity generation, commit evidence,
timestamp formatting or log consumers. Operational paths, event lists and retention guidance
remain in [LOG-REFERENCE.md](../LOG-REFERENCE.md) and [LOGROTATE.md](../LOGROTATE.md).

## Producers and consumers

| Log | Producer | Boundary |
| --- | --- | --- |
| `graphicalmatrix-audit.log` | `GraphicalMatrixAuditLogger` | Authentication, self-service and Admin API events; default Dashboard input. |
| `graphicalmatrix-sp-management-audit.log` | `GraphicalMatrixSpManagementTool.audit()`, `GraphicalMatrixSpGovernanceTool.audit()` | SP lifecycle, attributes, MFA and access-policy administration. |
| `graphicalmatrix-access-audit.log` | `GraphicalMatrixSpAccessFunction` | Runtime SP access-policy decisions. |
| `admin-audit.log` | `GraphicalMatrixAdminAuditTool` and `GraphicalMatrixAdminAuditWriter` | Supervised enrollment CLI operations, including CLI calls made by CSV provisioning. |
| `csv-import.log` | `graphicalmatrix-csv-import-runner.sh` | CSV delivery, snapshot digest, validation, apply and file disposition. |

These are separate streams, not a single transaction log. Dashboard does not automatically
ingest administrative CLI, SP management, access-policy or CSV logs. Official TOTP/WebAuthn
plugins, IdP logback, systemd journal and Apache/PHP retain their own logging configuration.
The administrative fields below belong to `admin-audit.log`; do not assume every stream has them.

## Administrative identity fields

`GraphicalMatrixAdminAuditTool.State` creates the base fields. `digest()` computes SHA-256
over UTF-8 input and emits lowercase hexadecimal. These IDs help correlate records; they
are not signatures, authorization tokens, idempotency keys or proof of database consistency.

| Field | Generation | Interpretation and limits |
| --- | --- | --- |
| `host` | OS hostname, with a Java hostname fallback; `unknown` if unavailable. | Originating host label, not a cryptographically verified machine identity. |
| `deployment_id` | SHA-256 of the resolved installation home from `home.toRealPath().toString()`. | Same real path produces the same value; symlink spellings resolving to it are equivalent. Moving the installation changes it. The hostname is not part of the digest, so different hosts using the same path can share it. Use with `host`. |
| `db_type` | JDBC URL prefix classification. | Supported database family: PostgreSQL or H2. |
| `db_target_id` | SHA-256 of the configured database target description, as detailed below. | Distinguishes configured connection targets, not physical database instances or database contents. |
| `op_id` | `UUID.randomUUID()` for each supervised CLI invocation. | Links its START, CHANGE and END events. Retrying the same command creates a new ID and may repeat writes; UUID uniqueness is probabilistic, not an integrity guarantee. |
| `parent_op_id` | `none` for the top-level operation; parent CLI UUID for a schema-init child. | `SCHEMA_INIT` receives a separate `op_id` linked to the enclosing operation. It can occur during a read or plan when auto-initialization is enabled. |
| `seq` | Increasing counter allocated by the supervising `State.record()`. | Parent and schema-child records share the counter. Filtering one `op_id` can leave gaps; it is not a global counter, database version or hash chain. |

### Database target derivation

The supervisor reads `DB_PROPERTIES` when supplied, otherwise
`conf/graphicalmatrix/db.properties` under the installation home.

- PostgreSQL identity uses the configured URI host, URI port and database path. URI user-info,
  query parameters and the DB login user are not included. No DNS resolution or server query
  is performed to discover a physical database identity.
- H2 identity uses the configured JDBC URL before the first `;` or `?`, not a separately
  resolved filesystem identity.
- Host aliases versus IPs, omitted versus explicit PostgreSQL ports, different path spellings
  or a moved H2 file can produce different IDs for the same underlying database.
- Conversely, a proxy endpoint can retain its ID while its backend changes. Restore or
  replacement at the same configured target does not necessarily change the ID.
- Missing URL settings use the implementation's default target description. Unparseable
  PostgreSQL descriptions can use fallback labels shared by unrelated configurations. The
  field alone therefore does not prove which database was actually reached or updated.

IdP and standalone Admin Tools can have different `deployment_id` values and the same
`db_target_id` when their target descriptions match. Changing only the database password or
login user does not distinguish otherwise identical PostgreSQL target descriptions.
Hashing avoids publishing raw connection descriptions, but predictable inputs can be guessed;
it is not encryption or a guarantee of anonymity.

## Following one operation

```text
one supervised RESET
  -> ADMIN_CLI_START  op_id=A, seq=1
  -> ADMIN_CLI_CHANGE op_id=A, seq=2, db_result=COMMITTED
  -> ADMIN_CLI_END    op_id=A, seq=3

optional schema initialization inside a CLI invocation
  -> schema records use op_id=B, parent_op_id=A
  -> the enclosing operation keeps op_id=A
```

Use `host` and `deployment_id` to locate the origin, `db_target_id` for configured target
correlation, and `op_id` / `parent_op_id` to follow the operation and its schema child.
Rows within one CSV invocation share the operation identity but have per-user reports.
CSV dry-run and apply are separate invocations, not a single `op_id`; compare the snapshot
SHA-256 and runner records when correlating them. A snapshot digest proves byte consistency,
not source authenticity or successful application.

The following read-only example searches an operation and its children in current/rotated
administrative logs. Replace the example UUID with the actual `op_id`.

```bash
sudo zgrep -hE \
  '(^| )(op_id|parent_op_id)=11111111-1111-4111-8111-111111111111( |$)' \
  /var/log/2faskw/admin-audit.log*
```

Do not assume that every START has an END after abrupt termination, or that a missing
record means no database change. This audit does not add an enrollment-history table.

## Results, actors and sensitive data

`result` describes the operation/stage outcome; `db_result` describes the reported database
outcome. `COMMITTED`, `ROLLED_BACK`, `NOT_ATTEMPTED`, `PARTIAL` and `UNKNOWN` must not be
inferred from the command exit status alone. A display failure after commit can yield a failed
command with `db_result=COMMITTED`. Missing commit acknowledgement remains unknown.
Exit 73 indicates a primary audit failure before a known/possible commit; exit 74 indicates
incomplete auditing after a known/possible commit. Do not blindly retry after 74.

The parent owns the mandatory local sink. Workers send typed receipts; raw SQL/stdout/stderr
are not treated as commit evidence. Database commits and filesystem audit writes are not
atomic. Optional syslog mirroring does not replace the local sink or guarantee remote retention.
Direct SQL, direct Java helpers, external LDAP edits and full database restore bypass this
supervised CLI boundary. A privileged log writer can still alter the files.

UID/EUID and effective-user fields describe the executing process. `invoker_claim` derived
from `SUDO_USER` has `invoker_source=unverified_environment`; do not treat it as independently
verified proof of the human operator. The IDs above do not strengthen that claim.

Administrative values percent-encode UTF-8 bytes for whitespace, `=`, `%` and other unsafe
characters. Decode according to that stream's format, not the historical authentication-log
escaping rules. Never add passwords, image sequences (including protected representations),
TOTP seeds, encryption keys, bearer tokens, raw argument lists or SQL to audit records.

## Timestamps and implementation map

Java audit producers share `GraphicalMatrixLogTimestamp`; CSV uses local `date` formatting.
Use ISO-8601 with `Z` or a numeric offset, preserve the underlying instant, and never sort
mixed-offset records solely by timestamp text. JVM/TZ overrides can differ from the OS setting;
restart long-lived JVMs after changing the OS timezone. Existing UTC lines remain readable.
DB epoch values, TOTP calculations, protocol/API timestamps and migration IDs are unchanged.
See [timestamp rules](../LOG-REFERENCE.md#log-timezone).

Implementation: `GraphicalMatrixAdminAuditTool.State` / `digest()`,
`GraphicalMatrixAdminAuditWriter.encode()` / `append()`,
`GraphicalMatrixAdminAuditClient.Transaction`, `GraphicalMatrixCliOperationReport`,
`GraphicalMatrixLogTimestamp` and the CSV runner. For completion-specific correlation, see
[MFA-SUCCESS-RECORD.md](./MFA-SUCCESS-RECORD.md); its event UUID is distinct from CLI `op_id`.

Primary tests: `GraphicalMatrixAdminAuditWriterTest`, `GraphicalMatrixAdminAuditStateTest`,
`GraphicalMatrixAdminAuditCliTest`, `GraphicalMatrixCliSqlCommandTest`,
`GraphicalMatrixLogTimestampTest`, Dashboard parser/completion tests, and
`scripts/tests/log-timezone-regression.sh`. These tests do not establish global ID uniqueness,
certify human identity, or prove production PostgreSQL/LDAP failure recovery.
