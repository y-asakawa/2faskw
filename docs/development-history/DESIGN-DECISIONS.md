# Design Decisions

This document records significant 2FAS-KW design decisions and the implementation
evidence supporting them.

For the v1.3.4 baseline, the entries below are **2026/08 reviews of the existing
implementation**. They should not be represented as records written when the original
design decisions were first made unless Git history independently establishes that
fact.

## DD-CLI-AUDIT-001: Keep administrative audit ownership in the CLI parent

- **Status:** Implemented for v1.4.2; unpublished development branch
- **Area:** Administrative CLI, standalone tools, CSV provisioning

### Decision

Persist a mandatory root-controlled administrative audit outside the IdP tree. The parent CLI owns
the sink; JDBC workers send typed commit receipts through a short-lived local Unix domain socket.
H2 workers may run as Jetty without gaining audit write permissions. Existing authentication auditing
and official factor plugins are unchanged. Audit events exclude credentials and raw SQL/output.

### Rationale and boundaries

A command exit status is not a commit receipt: display or connection close can fail after commit.
Stage receipts preserve partial physical deletion and migration batches, while an unacknowledged commit
remains UNKNOWN. CSV per-user success is published only after its transaction commits. Primary audit
failure stops unstarted writes, or reports incomplete auditing after known/possible commit; there is no
database/filesystem atomic transaction. The root/administrative writer trust boundary is explicit:
direct SQL, direct Java execution and malicious writers cannot be made tamper-proof by this log.

### Evidence

`GraphicalMatrixAdminAuditTool`, `GraphicalMatrixAdminAuditWriter`, `GraphicalMatrixAdminAuditClient`,
`GraphicalMatrixCliOperationReport`, `GraphicalMatrixCliSqlCommand`, and their writer/state/CLI/SQL tests.
See [v1.4.2 implementation design](../release-notes/v1.4.2-CLI-ADMIN-AUDIT-DESIGN.md).

### Patent-review impact

NO: no change to image selection, credential derivation or authentication algorithms.

## DD-LOG-TIME-001: Use the execution environment's timezone for log presentation

- **Status:** Implemented for v1.4.2; unpublished development branch
- **Area:** Authentication, SP management, access control, administrative CLI and CSV logs

### Decision

Use `GraphicalMatrixLogTimestamp` for Java audit timestamps and the local `date` offset for
CSV runner logs. Emit ISO-8601 with `Z` or a numeric offset. Preserve each event's instant and
Java fractional precision; calculate daylight-saving offsets at that instant. Do not add a
separate timezone property. JVM/TZ overrides remain effective; restart long-lived JVMs after
changing the OS timezone.

### Rationale and boundaries

Operators can correlate logs with the host clock without losing unambiguous timestamps.
Existing UTC lines remain valid alongside offset lines. Dashboard parsers normalize both to
an instant and retain MFA completion epoch checks. DB values, TOTP calculations, API timestamps,
registry/metadata and migration IDs are unchanged. IdP logback and other products' logging
configuration remain administrator-controlled. CSV timestamp failures stop rather than emit
an empty timestamp or silently fall back to UTC.

### Evidence

`GraphicalMatrixLogTimestampTest`, management/governance audit integration tests,
`scripts/tests/log-timezone-regression.sh`, and Dashboard parser/completion tests cover UTC,
JST, fractional/negative offsets, local date boundaries, DST overlap and mixed historical logs.
See [log timestamp rules](../LOG-REFERENCE.md#log-timezone).

### Patent-review impact

NO: only log presentation changes; authentication algorithms and credential models are unchanged.

## DD-GM-001: Use image IDs rather than display positions as credential elements

- **Status:** Implemented in v1.3.4
- **Recorded:** 2026/08 (implementation review)
- **Area:** GraphicalMatrix

### Decision

GraphicalMatrix treats the selected image identifiers, not their current screen
coordinates, as the credential elements.

### Verified implementation

The browser obtains `tile.dataset.id`, appends the image ID to the selection array in
click order, and submits the comma-separated sequence. Server-side verification
receives the selected IDs and validates them against the display order and registered
credential representation.

### Evidence

- `assets/graphicalmatrix.js`: selection handler and `selected.join(",")`
- `GraphicalMatrixVerifyServlet.doPost()`
- `GraphicalMatrixRepository.verify()` / `verifyInTransaction()`

### Security/design consequence

Randomizing screen placement does not change the identity of the selected credential
elements.

## DD-GM-002: Randomize image display order

- **Status:** Implemented in v1.3.4
- **Recorded:** 2026/08 (implementation review)

### Decision

Create a fresh display order from configured graphical IDs using a cryptographically
strong random source.

### Verified implementation

`GraphicalMatrixSupport.shuffledGraphicalIds()` copies the configured IDs and applies
`Collections.shuffle(..., RNG)`, with `RNG` initialized using `SecureRandom`.

### Evidence

- `src/main/java/io/github/yasakawa/faskw/GraphicalMatrixSupport.java`
- `GraphicalMatrixSupport.shuffledGraphicalIds()`

## DD-GM-003: Preserve image-selection order when ordered mode is enabled

- **Status:** Implemented in v1.3.4
- **Recorded:** 2026/08 (implementation review)

### Decision

When ordered selection is required, the order of selected image IDs is part of the
credential.

### Verified implementation

The browser appends IDs in click order. `GraphicalMatrixSequenceStorage.canonical()`
preserves the sequence order in ordered mode instead of sorting it. The non-hash
matching path compares ordered lists directly.

### Evidence

- `graphicalmatrix.properties`: `graphicalmatrix.order = 1`
- `GraphicalMatrixConfig.isOrderedSelectionRequired()`
- `GraphicalMatrixSequenceStorage.canonical()`
- `GraphicalMatrixSequenceStorage.matched()`

### Consequence

For ordered mode, `A,B,C,D` and `A,C,B,D` represent different credential sequences.

## DD-GM-004: Store a non-recoverable reference value in hash mode

- **Status:** Implemented in v1.3.4
- **Recorded:** 2026/08 (implementation review)

### Decision

In hash storage mode, do not store the recoverable ordered image-ID sequence as the
authentication verifier. Generate a reference value using HMAC-SHA256 with a random
salt and pepper.

### Verified implementation

`GraphicalMatrixSequenceStorage` canonicalizes the sequence, generates/uses salt, and
calculates HMAC-SHA256 using the pepper as key. Authentication recomputes the digest
and compares it using `MessageDigest.isEqual()`.

### Evidence

- `GraphicalMatrixSequenceStorage.encode()`
- `GraphicalMatrixSequenceStorage.matches()`
- `GraphicalMatrixSequenceStorage.canonical()`
- `GraphicalMatrixSequenceStorage.hmac()`
- `postgresql-schema.sql`: `graphicalmatrix_enrollment.sequence`

### Note

The v1.3.4 configuration records `graphicalmatrix.sequence.storage = auto`, with the
reviewed implementation resolving `auto` to hash mode.

## DD-GM-005: Validate submitted selections server-side

- **Status:** Implemented in v1.3.4
- **Recorded:** 2026/08 (implementation review)

### Decision

Do not rely solely on browser-side selection controls. Validate the submitted
selection shape and displayed-image membership on the server before credential
comparison.

### Verified implementation

The repository verifies expected selection count, duplicate constraints and
membership in the server-known display order before calling the sequence verifier.

### Evidence

- `GraphicalMatrixRepository.verifyInTransaction()`

## DD-GM-006: Keep credential semantics separate from reviewed patent models

- **Status:** Observation of v1.3.4 implementation; patent-impact guardrail for future
  changes
- **Recorded:** 2026/08

### Current implementation

GraphicalMatrix v1.3.4 uses an ordered image-ID sequence and reference-value
comparison.

The reviewed authentication path does not use:

- a credential consisting of a key-top group split into multiple states followed by
  selection of all elements of a least-populated state; or
- a separate authentication auxiliary information credential whose operation
  position is compared with the operation position of authentication information.

### Related review

- [PATENT-REVIEW.md](../PATENT-REVIEW.md)
- Internal detailed claim charts for JP5705165B2 and JP5705167B2

### Change-control consequence

Any future feature introducing image groups, state classification, auxiliary
authentication information, or relative operation-position relationships should set:

```text
PATENT-REVIEW impact: YES
```

and trigger a new technical review before release.

## Template for future decisions

```text
## DD-<AREA>-NNN: <title>

Status:
Date:
Issue:
PR:
Commit:
Public sources:

### Context

### Decision

### Alternatives considered

### Security / interoperability consequences

### Implementation evidence

### Patent-review impact

YES / NO

### Notes
```
