# SP Import Tools Design Decision Record

## Context

The requested workflow imports SP registration information from an ordinary
Shibboleth IdP into a separate 2FAS-KW IdP. It is a one-time migration aid rather
than a persistent management backend or synchronization service.

## Decisions

1. Keep the existing authentication, storage, and SP management implementation unchanged.
2. Invest in static source analysis and explicit unsupported-case diagnostics.
3. Register through the current public CLI instead of editing target XML/JSON directly.
4. Use one Bash entry point and a standalone Java 21 module; no runtime Python dependency.
5. Store reports and metadata under the extracted tool's `sp-migration/` directory.
6. Use Admin Tools' common build entry, version settings, fixed/versioned ZIP convention,
   checksum list, and release signing key.
7. Retain successful operations after partial failure; resume checks target state and
   does not automatically delete, update, or re-add existing registrations.
8. Keep inline/ambiguous aggregate extraction, unknown dynamic conditions, and complex
   source policies on hold; explicit generic snapshots require independent review.

## Rationale and consequences

The standalone module follows the project's Java runtime while avoiding classpath
coupling to a source IdP. Common release packaging does not require runtime integration.
TXT reports make reasons and remedies visible; internal TSV records are only checks.
Source files remain read-only. A CLI-compatible registration is not a guarantee of
unchanged federation behavior or successful SP-side SSO.

Shared locks and bulk rollback would require invasive changes to existing management.
They are deliberately excluded. Administrators must serialize target changes and
retain normal backups. Partial approval/profile creation is reported rather than hidden.

## Implementation evidence

- Source: `sp-import-tools/src/main/java/io/github/yasakawa/faskw/spimport/`.
- Tests: `sp-import-tools/src/test/java/io/github/yasakawa/faskw/spimport/`.
- Packaging: `scripts/build-sp-import-tools-package.sh`, `scripts/check-sp-import-package.py`,
  and the additions to the common build/checksum/signature lists.
- Operations: `docs/SP-INPORT-TOOLS.md` and `docs/release-notes/v1.4.0-SP-IMPORT-TOOLS.md`.

This record describes the local v1.4.0 implementation work. No issue, PR, commit, or
published release identity is asserted for an uncommitted working tree.

## Managed-source migration extension

The follow-up requirement preserves existing 2FAS-KW SP management intent while
moving to a separate IdP. It does not turn the importer into a management backend.

1. Auto-detect the registry and fail closed on damage; retain explicit generic mode
   and the original generic bundle format.
2. Compare registry intent with provider/metadata/filter/catalog/MFA/access reality.
   Schema 1 access flags are absent evidence, not an instruction to disable access.
3. Export a versioned normalized intent instead of copying whole management JSON,
   legacy XML, revisions, audit history, users or credentials.
4. Use a strict Jackson core adapter in the standalone module. Package its license,
   dependency inventory and hashed SBOM without changing the IdP's classpath.
5. Require global MFA equivalence as a precondition; never silently migrate network
   exceptions or alter target-wide policy during per-SP registration.
6. Prepare resolver/mapping, ACS hosts and ContextCheck separately. Preserve reviewed
   target approval/description differences and never downgrade release to access.
7. Bind final intent rather than transient phase booleans in resume plans. Reverify
   an existing SP and continue missing access stages, including set-before-disable.
8. Require actual target isolation and exclusive administration. The CLI's add/access
   operations are not atomic as a group, so a confirmation flag cannot replace an
   edge control, backup or browser cutover gate.
9. Separate CONFIG_VERIFIED from readiness and successful browser SSO. Keep both
   cutover statuses unconfirmed until the administrator completes operational tests.

The extension is implemented only in the standalone importer/build/docs. Existing
authentication, self-service, persistence and SP CLI implementation are unchanged.
Tests use real file formats and a stateful mock target CLI, including partial failure
and resume. Static configuration verification is not claimed to certify deployed
runtime behavior. Unknown/custom RP overrides remain held, not implicitly discarded.

## Standard attribute-filter compatibility

Deployed IdP defaults exposed unsupported childless `permitAny` rules and
metadata-driven `EntityAttributeExactMatch` requirements/permits. The standalone
analyzer now resolves these forms, including all-value AND/OR, against explicit
raw entity-level metadata tags. Missing tags mean no release only when that raw
context is known; provider filters, mapped registry modes and complex values remain
held. TXT evidence records YES/NO/UNKNOWN with locations and unresolved reasons.

This change does not execute source filters, rewrite source settings,
or change IdP authentication/management code. Reanalysis
uses a new export directory instead of overwriting old bundles. Additional tests
cover both positive compatibility and absence/unknown cases, byte preservation,
bundle round trips, and scoped managed-profile drift.

## Owned policy and common release separation

A deployed `uid-mail-release` profile legitimately coexists with an `alwaysRelease`
policy adding `schacHomeOrganization`. Comparing the whole effective union to the
profile incorrectly held otherwise consistent managed registrations.

The importer now compares the exact CLI-owned `2faskw-NAME` policy to its profile,
while retaining additional attributes as a separate contract. Common policies are
neither discarded nor merged into profiles or copied to the target. Unknown conditions,
missing/duplicate owned rules and actual profile drift still hold the candidate.

Before migration, incoming metadata is evaluated against the initialized target's
policies and known unfiltered provider context. Additional sets must match, and their
resolver/mapping must be ready. Custom resource hashes and semantic rechecks before/
after each CLI write detect reviewed-target changes. Differences produce explicit TXT
diagnostics and stop, rather than broadening release automatically. Partially successful
writes remain and require isolation. Older bundles preserve their original contract;
fresh reanalysis is required to use the new separation. No IdP core code is changed.

## Registration versus source authorization

The detailed inventory exposed the former 4 MiB manifest limit as too small for
whole-IdP analysis. The manifest-only limit is now 32 MiB across export, reload
and execution fingerprints, without increasing JSON or resume-plan limits.
Shared blocking diagnostics are serialized once and restored before candidate
validation. This is a bounded capacity fix, not a relaxation of holds or access
requirements; synthetic 248-candidate round-trip tests preserve both.

An ordinary Shibboleth source contained SP-specific RP overrides and ContextCheck
rules. The original blanket RP hold obscured two different questions: whether an
SP registration can be reproduced, and whether its authorization has been migrated.
The importer now reports these independently in generic mode.

Exact literal RelyingPartyByName selectors scope evidence to matching SPs. A static,
non-executing ContextCheck parser describes supported boolean/attribute/regex shapes;
unknown scopes/functions/resources remain explicitly unresolved. The generic v3 bundle
retains the assessment across transfer. Legacy v1 is readable but authorization is unassessed.
Managed v2, source drift checks and managed target-release equivalence remain unchanged.

Allowing registration without source authorization can broaden access. Consequently,
pending generic apply requires registration-only intent, administrator-enforced isolation
and a typed confirmation before any CLI write. Verification only proves registration;
exit 10 and pending-access result records persist. Authorization must be reproduced and
allow/deny browser tests completed before exposure. No automatic policy conversion or
new installed IdP management mechanism is introduced. Ambiguous aggregate and mutating-filter holds remain.

## Ordinary IdP framework and aggregate snapshot refinement

A large ordinary IdP inventory exposed two separate limitations: raw aggregate
metadata was inventory-only, and every imported standard framework class was
classified as an unknown custom authorization bean. Raising capacity alone did
not make those candidates useful.

Expected framework resource paths/classes now produce a compact manual-review
summary, not a no-restriction assertion. Scalar/X509 support is separated; unknown
custom classes, paths, dynamic definitions and source predicates remain unresolved.
Matched SP-specific ContextCheck evidence and pending-authorization gates remain.

Generic source analysis can explicitly request unsigned independent snapshots.
Namespace/language context and restrictive parent validity are preserved. Original
bytes/signatures are retained as hash-bound evidence; transformed copies do not
inherit signature assurance. Parent Extensions/xml:base/unknown context stay held.
Only recognized verification filters and explicit SP-only role filters are classified;
trust engines and arbitrary filters are not executed. Missing required signatures
and invalid explicit validity are rejected.

Generic manifest v4 adds original-source R and candidate-reference X records.
Apply adds independent metadata review, a typed acknowledgement and all-before-write
guards; plan-v3 pins that flag for resume. Old generic v1/v3 and managed v2 remain
readable, but old plans/bundles are not silently upgraded to new approvals/extraction.
Tests include 248-candidate round trips, namespace/expiry/provenance validation,
custom framework lookalikes and cancellation/change/no-write cases. No source IdP,
core authentication, DB or deployed SP governance implementation is changed.

## Human-Readable Review Guidance

Large inventories showed that English status codes alone did not tell administrators
what to verify. A read-only presentation layer now lists per-SP checks and grouped
hold reasons, with localized instructions and preserved original technical evidence.
The wording deliberately avoids claiming that REVIEW is verification success or that
every validity-related diagnostic proves expiration. Pre-registration prerequisites
and isolated post-registration qualification are distinct.

Interactive filtered views retain original numbers and eligibility/selection rules.
All-held results remain inspectable without enabling writes. Optional checklist TXT
output is never an approval source. Existing manifests, canonical evidence, typed
confirmations, isolation controls, exit codes and registration mechanisms stay intact.

### Follow-Up: Actionable Hold Evidence

A large source inventory exposed two diagnostic gaps: validity failures collapsed
several causes into one sentence, and unsupported attribute conditions omitted their
actual operands. Diagnostics now retain actual validity/cache values, one UTC analysis
timestamp, filter/window settings, and the failing entity/ancestor location. Attribute
holds identify policy, attribute and bounded rule structure without executing scripts
or printing script text/arbitrary attribute values. Parent facts survive failed extraction.

Common review obligations now appear once per overview; per-SP sections show their
specific inputs and restrictions. Detail views remain self-contained. Existing blocking
codes and registration/approval/isolation gates are preserved, and legacy diagnostics
are not retroactively enriched with guessed values.

## Attribution Alignment

Java production and test sources use the IdP plugin's Apache 2.0 header with
`Copyright 2026 Yoshifumi ASAKAWA`. Maven organization/license metadata matches
the main project. The JAR includes its implementation title/version and
`Implementation-Vendor: 2FAS-KW Project`, enforced by the package checker.
The existing distribution LICENSE/NOTICE are retained. Author metadata is not
a cryptographic signature; release archive signing remains a separate build step.

## Japanese Distribution README

The generated package README now uses Japanese instructions and shell comments.
Executable command lines, option names, paths, diagnostic identifiers and typed
acknowledgements are preserved. The source/target distinction, review obligations,
transfer safeguards, isolation requirements, resume behavior and compatibility limits
remain explicit. The build template is the maintained source, so future packages
retain the translation instead of overwriting a hand-edited generated README.

## Dedicated Java Runtime Guidance

The package README and manual document a separate Linux Corretto 21 tarball runtime
for older source IdPs. The procedure verifies the official HTTPS SHA-256, rejects an
existing destination, extracts the complete JDK with root ownership, checks library
access and both normal/sudo startup, and selects JAVA per command. It never changes
system alternatives or IdP service Java settings. Old-OS compatibility and actual
Linux installation remain external qualification requirements.

## Cause Classification and Per-SP Work Instructions

A real inventory contained both expired aggregate metadata and attribute release holds.
The latter mixed an ID with trailing whitespace and intentional value-limited release.
Treating every unsupported rule as a source error would encourage unsafe broadening.

New diagnostics distinguish exact ID review, recognized value/deny shapes and unresolved
conditions while retaining existing blocking codes. The read-only guidance layer groups
work by cause and orders source review/refresh, manual equivalent migration, extraction
choices, authorization reconstruction and target qualification. Detailed work names each
SP's actual operands/locations and requires filtered AACLI/received-attribute tests,
including nonmatching and mixed multivalued cases. It does not execute or normalize
source conditions, and manual conditions remain held even after target reconstruction.

Optional localized `migration-actions.txt` preserves a portable per-SP work report,
not an executable or approved plan. Model-derived display, old-bundle compatibility,
canonical fingerprints, eligibility, isolation and confirmations remain unchanged.
Shared parent-metadata repair is explicitly not proof that other holds are resolved.
No existing authentication, DB or deployed SP governance behavior is changed.

## Two-Stage Registration and Validity Revalidation

Operational review favored one-time SP registration followed by separately reviewed
attribute/authorization configuration. Added explicit generic `--defer-attributes`,
not an implicit fallback from full migration. Canonical evidence stays unchanged;
candidate-local attribute holds are displayed as pending reviews only in this mode.
Other holds and the managed policy contract are preserved.

The target uses the verified builtin empty profile none and never approves attributes
or creates profiles in this branch. Typed isolation acknowledgement is mandatory for
apply. Exit 11, pending stage records and a private per-SP attribute work report prevent
registration success from being mistaken for a completed migration. Shared target
release policies may still send attributes; administrators must keep the target isolated
until phase-two filter, authorization and browser testing completes.

Resume plans bind the new mode and preserve exact existing-SP verification. The tool
does not reset profiles already changed during phase two. Metadata is revalidated at
target preparation, after interactive approval before any writes, and immediately before
each add apply, including managed migration. An expired bundle requires a trusted new
export; static snapshots do not inherit automatic HTTP metadata refresh. Regression
tests cover pending results, no attribute writes, retained holds, isolation gates,
mode-bound plans, existing profile conflicts and expiry during review/CLI dry-run.
