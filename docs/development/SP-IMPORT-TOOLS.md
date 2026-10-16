# SP Import Tools Internals

## Boundary

`sp-import-tools/` is a standalone Java 21 Maven project. It does not link against
the IdP runtime, Spring, OpenSAML, or the existing plugin implementation. Bash only
locates the packaged JAR and Java runtime. Runtime Python is not required.
The tool reads source files and calls the installed target `graphicalmatrix-sp.sh`.
It never edits source configuration, target registries, schemas, or authentication code.

## Call graph and responsibilities

```text
graphicalmatrix-sp-import.sh
  -> SpImportMain.run
       -> ImportOptions.validate
       -> ManagedSourceAnalyzer.analyze OR MigrationBundle.load
            -> SourceAnalyzer.analyze (generic static XML traversal)
                 -> SourceAccessAnalyzer.assess (generic authorization evidence only)
            -> ManagedData / ManagedAccessWiring (managed source only)
       -> MigrationBundle.save (source export only)
       -> select / source review / target choices
       -> ExistingCli.prepare OR ManagedMigration.prepare
       -> bundle and previous plan checks
       -> ExistingCli.execute OR ManagedMigration.execute
            -> existing approve/profile/add dry-run/apply/verify
            -> progress records and TXT results
```

| Component | Important methods | Responsibility |
|---|---|---|
| `SourceAnalyzer` | `resources`, `staticResources`, `walkConfig`, `resolvePath` | Follow referenced static resources without executing beans/scripts |
| `SourceAnalyzer` | `provider`, `metadata`, `entity`, `analyzePolicies` | Inventory metadata and conservatively classify migration conditions |
| `SourceAccessAnalyzer` | `assess`, `expression`, `literalValues` | SP-scoped RP/ContextCheck evidence without executing or translating authorization |
| `SecureXml` | `parse`, SAX `Handler` | Namespaces, measured line/column, external-reference denial, depth/node limits |
| `MigrationBundle` | `save`, `load`, `detail` | TXT reports, versioned TSV format, hash/entity/path checks |
| `ExistingCli` | `prepare`, `execute`, `verify`, `process` | Sequential CLI orchestration, explicit approval, bounded process output/time |
| `SpImportMain` | `run`, `select` | Mode validation, terminal confirmation, immutable input recheck, result ownership |
| `ManagedSourceAnalyzer` | `analyze`, `hash` | Mode detection, registry/XML/catalog/MFA/access cross-checks, snapshot change detection |
| `ManagedData` | `registry`, `catalog`, `policies`, `properties`, `intent` | Strict read-only adapters and normalized migration intent |
| `ReleasePolicies` | `source`, `additional` | Separate the exact CLI-owned policy/profile from additional effective releases |
| `StrictJson` | `parse`, `read`, `fields`, `write` | Bounded JSON trees, duplicate/type/unknown-field rejection, stable key ordering |
| `ManagedAccessWiring` | `require` | Reject unknown source ContextCheck functions/conditions or missing SAML2 wiring |
| `ManagedMigration` | `prepare`, `prepareApproval`, `planText` | Target prerequisites, conflict checks, shared dependencies and phase-independent plan identity |
| `ManagedMigration` | `execute`, `applyAccess`, `verifyProfile`, `verifyAccess` | Existing-CLI-only writes, exact approval/policy verification and partial resume |
| `ManagedMigration` | `snapshot`, `assertBaseline`, `write`, `stage` | Target change detection and durable stage records without claiming shared locks |
| `SourceAnalyzer` / `ManagedMigration` | `analyzeReleases`, `probeProviders`, `checkReleases` | Evaluate incoming snapshots against target policies and pin/recheck all resolved inputs |
| `SafeFiles` | `read`, `write`, `append`, `contained`, `display` | Bounded input, private outputs with pinned directories and no-follow writes, bundle containment, terminal-safe rendering |
| `MetadataSnapshots` | constructor, `extract` | Bounded secure DOM parse; explicit unsigned independent snapshots, inherited namespaces/language/expiry, SP-only role filtering and provenance facts |
| `SourceAnalyzer` | `describeFilter`, `validateReviewFilters` | Classify exact known static verification/role filter shapes; fail missing required signature/validity; never execute trust engines |
| `SourceAccessAnalyzer` | `standardSupport`, `standardFrameworkResource` | Separate literal scalar/X509 support; summarize expected system resources as manual review, not unknown custom predicates |
| `ExistingCli` | `authorizeMetadataReview`, `metadataStamp` | Additional no-write gate, typed independent-metadata acknowledgement and evidence-bound execution approval |

## Conservative analysis

Do not scan every XML file as if it were active configuration. Follow static
service resource lists and imports; label conventional path fallbacks explicitly.
Unknown resource factories, conflicting property precedence, unknown/mutating provider filters,
nontrivial value/deny release rules remain registration holds. Generic RP/ContextCheck
dependencies are assessed separately; managed mode and target hypothetical-release
analysis retain their original conservative override holds.
Simple Requester/ANY/AND/OR applicability and all-value permit rules can produce an
attribute set. Childless `permitAny="true"` (or XML boolean `1`) is supported;
conflicting child rules or invalid booleans are held. Explicit false permits still
require a supported PermitValueRule.

`SourceAnalyzer.condition` evaluates `EntityAttributeExactMatch` in requirements
and permits, including nested all-value AND/OR. `entityAttribute` examines only
entity-level MD Extensions / MDATTR EntityAttributes / SAML2 Attribute values.
Literal Name, explicit NameFormat, and exact string Value must match. Only absent
or false/0 `ignoreUnmappedEntityAttributes` is supported. AttributeRegistry mapping,
dynamic expressions, assertions, complex/nil/non-string values, and unknown rule
options remain unresolved. Unsupported provider filters invalidate raw tag evidence
even if a matching tag is already present. Ambiguous inline/aggregate parent context is not inferred.
Known static signature/validity checks are review items, not tag mutations or verified trust.
YES adds the attribute, NO does not, and UNKNOWN holds the candidate unless a
supported boolean combination can conclusively determine its all/none result.
The original metadata bytes remain unchanged; no source XML or registry is rewritten.
Facts include match results and concrete unresolved reasons with source locations.
SP RequestedAttribute and role-level tag elements are not release approvals.

The resulting attribute set is a snapshot, not a runtime metadata-driven rule.
Managed-source comparison requires exact equivalence between the CLI-owned
`2faskw-NAME` policy and the managed profile, not the union of every matching policy.
`ReleasePolicies` validates a direct exact Requester and all-value rules. A missing
nonempty-profile policy, duplicate owned policy, changed requester/attributes, or
another managed SP policy releasing values is held. Common rules cannot mask drift.
An empty profile may have no owned policy, as in the existing CLI renderer.
Additional releases are the union of other applicable policies minus profile attributes;
overlap does not remove attributes from the managed profile. They remain in the
effective report and are not copied into profile creation or approval dependencies.
`SourceAttributePolicyTest` covers these forms, negative/context cases, evidence,
and bundle round trips. `ManagedSourceAnalyzerTest` retains the scoped profile-drift gate.
`ManagedReleaseMigrationTest` covers source separation and target equivalence.

Inline descriptors remain inventory-only. File aggregates are held by default;
generic `--extract-aggregate` explicitly opts into the bounded snapshots described below.
Parent signature/namespace/group/validity context must not be discarded silently.
MDQ cannot enumerate all SPs. Dynamic/remote cached metadata is a snapshot with
changed refresh semantics. Network fetching is not part of initial analysis.

## Storage and state

Default results are relative to the real launcher directory, not the current working
directory or root's home. Existing exports are never overwritten. TXT files are the
user interface; Base64-encoded TSV fields are internal records, not executable plans.
Input hashes detect changes; they are not proofs of authenticity.

Application uses a private temporary metadata snapshot. Each existing CLI command
retains its own transaction/recovery behavior. There is no shared CLI lock, bulk
transaction, automatic source deletion, or whole-migration rollback.
Successful approvals/profiles remain if a later operation fails.
Resume compares the previous plan and checks actual target identity, hash, profile,
MFA, CIDRs, managed policy drift, and enabled state. Verification failure never triggers automatic re-addition.
Privileged output requires a POSIX filesystem supporting Java SecureDirectoryStream.
On providers without that API, non-root analysis/dry-run uses create-new/no-follow
channels and rejects symlink parents; privileged operations fail closed.

## Build and compatibility

`build-plugin-package.sh` invokes the independent package builder using `version.ini`.
The builder validates Maven and JAR versions, produces byte-identical fixed/versioned
ZIPs, generates content manifests, and validates a strict package allowlist.
Common checksum and signing code covers both new archives. Existing package rules
are not loosened. Detailed docs remain outside the ZIP and are linked by version.

Source limits are 1000 files, 256 MiB combined input, 50 MiB per file, 32 import
levels, 64 XML levels, 200000 XML nodes per document, and 10000 SP candidates.
These are defensive limits, not measured capacity or throughput guarantees.
Target CLI versions are output-contract checks, not claims of full SSO qualification.

## Tests and extension points

`SourceAnalyzerTest` covers reference traversal, policies, provider types, snapshot
mapping, property escapes, and XML protections. `MigrationBundleTest` covers paths,
permissions, changed bytes, format handling, and output placement. `ExistingCliTest`
covers command arguments, dry-run safety, approval/profile/add ordering, partial apply
failure stopping, target preparation, and CIDR-aware resume verification.
`SpImportMainTest` exercises the source-to-report entry flow, held reports,
overwrite refusal, and source/target separation without touching a real IdP.
`check-sp-import-package.py` and package regression tests cover static distribution.

Adding a supported source condition requires fixtures proving its applicability and
equivalence to target CLI behavior. Never clear a hold merely to finish migration.
Changes to target output parsing require version/contract tests. Real browser SSO,
IdP reload behavior, and SP-side trust remain separate environment tests.

## Generic registration and authorization separation

`SourceAnalyzer` enables separation only for generic source analysis. Managed-source
analysis and `analyzeReleases` used for managed target equivalence keep their existing
hold rules. There are no changes to plugin authentication or the installed SP CLI.

`SourceAccessAnalyzer.assess` scopes literal RelyingPartyByName overrides by exact
entityID. Unknown selectors are not treated as non-matches. It reads a conventional
ContextCheck resource only when needed; `--context-check-config` selects a standalone
snapshot. The source reader includes successful reads in consistency/hash checks.
Imports, references, custom Functions, expressions and unsupported predicate shapes
remain unresolved; the analyzer never creates a Spring context or executes code.

The bounded expression tree supports AND/OR/NOT, literal SP selectors, simple
attribute maps and regex predicates. SP-only branches are simplified; predicate
logic, regex text and filtered/unfiltered/unspecified attribute sources are retained.
This is descriptive evidence, not an executable policy or a runtime truth evaluation.
Attribute absence, scoped values, wildcard matching and NOT must be manually reviewed.
Profile overrides also carry NameID/authentication/encryption/intercept review duties.

Generic manifest v4 retains mandatory `A` records keyed by unique candidate name.
Legacy v3 assessments are also readable; v4 adds original-source evidence described below.
Statuses are NONE_OBSERVED, MANUAL_REQUIRED, UNRESOLVED and LEGACY_UNASSESSED.
Missing, duplicate, invalid or orphan assessments fail loading. A v1 bundle remains
readable but cannot imply authorization equivalence; its previous holds remain.
Control characters in evidence are escaped before serialization for stable round trips.
Evidence is bounded to 1000 items per candidate; a manifest exceeding 32 MiB is not published.
`MigrationBundle.MAX_MANIFEST_BYTES`, `readManifest` and `fingerprint` share the
same limit for export, reload and pre-execution hash checks. JSON inputs and resume
plans retain their separate 4 MiB limits. Common BLOCK/ERROR diagnostics are stored
once as global D records and restored to every candidate before validating missing
metadata, managed intent or duplicate entities. TXT reports retain those reasons;
reducing duplicate storage never removes a hold or source authorization evidence.
Regression coverage includes a synthetic 248-candidate manifest above 4 MiB,
round-trip preservation, inherited holds and explicit oversize rejection.
TXT inventory/detail and `access-control.txt` expose both registration and access states.
NONE_OBSERVED means no dependency detected by supported static analysis, not proof of
unrestricted runtime access. Managed candidates keep MANAGED_POLICY_WORKFLOW reporting.

Pending generic apply requires `--registration-only --confirm-isolated-target` and
the exact interactive phrase REGISTER ONLY ISOLATED, in addition to ordinary review.
`ExistingCli.authorizeRegistrationOnly` pins each assessment; `executeStaged` checks
all pending plans before approvals, profiles or adds can write. Flags do not establish
network isolation. Generic import never runs access set/disable. Managed bundles reject
the registration-only shortcut and continue their full policy workflow.

Generic plan-v3 pins target canonical path, registration/isolation/metadata-review flags, bundle hash and selected CLI
choices. Reload checks include assessment equality. Resume requires the same plan;
old plan-v1/v2 is not automatically resumed. Verification of an already registered SP
does not clear pending authorization. Results use REGISTERED_ACCESS_PENDING or
VERIFIED_EXISTING_ACCESS_PENDING, and `access-control-status.txt` states that source
rules and browser authorization tests were not applied/checked. Successful preparation,
dry-run or registration with pending authorization returns exit 10, not full success.
Registration holds or actual prerequisites/CLI errors retain their own error codes.
Operators must keep the target isolated until manual authorization and allow/deny tests pass.

Tests cover literal scoping, branch fallback, nested bean IDs, AND/OR/NOT, regex preservation,
missing/custom context, explicit resource paths and hashes, no-write gates, cancellation,
changed assessments, partial/resumed registration status, v4/v3 validation and legacy v1.
Arbitrary metadata filter execution, ambiguous aggregate extraction and value-specific
release translation remain unsupported. This does not make federation inventories automatically migratable.

## Explicit generic metadata snapshots

`--extract-aggregate` is source-only and rejected for managed mode. `MetadataSnapshots`
uses a bounded SAX check followed by secure namespace-aware DOM parsing and secure
serialization; DTDs, external entities/schema/stylesheets and XInclude are disabled.
Namespaces (including QName bindings and nested shadowing), XML language and mixed
content are preserved. Only direct SAML EntitiesDescriptor ancestry is accepted.
Parent Extensions, xml:base, unknown parent elements/attributes, malformed/expired
validity and variable month/year durations remain holds. The earliest parent/entity
validUntil and minimum fixed cache duration apply. The latter also caps static snapshot
expiry from analysis time. Group names are evidence, not silently migrated group rules.
Source signatures are removed from transformed copies and are NEVER claimed valid.
Original source bytes remain unchanged and are stored once by SHA-256 under
`evidence/metadata-sources/`. v4 R records name hashes; X records link candidates to
originals. Loading checks bounds, hash, root, entityID membership and orphan/missing
references. Original/standalone metadata share the existing 256 MiB total bound.

Known static SignatureValidation and RequiredValidUntil settings become mandatory
review diagnostics. Missing explicitly required root signatures and invalid/missing/
out-of-window explicit validity are blocked. Omitted version-dependent defaults are
not inferred. ChainingFilter order and original settings remain in evidence. Only an
explicit SPSSODescriptor-only EntityRole/EntityRoleWhiteList with known boolean settings
can transform roles under extraction opt-in; other role/mutating/custom shapes stay held.
No filter engine, XML signature trust engine, remote fetch or source runtime is executed.

Generic apply for these candidates needs `--confirm-metadata-review` and the exact
phrase VERIFY SOURCE METADATA before preparation/writes. `executeStaged` checks all
plans' evidence-bound approvals before writing. `result.txt` records administrator
acknowledgement, not cryptographic assurance. Hash consistency is not authenticity.
Pending authorization still needs separate registration-only/isolation confirmation.
Old bundles are not auto-re-extracted; operators must reanalyze with current tools on
both hosts. Managed v2 and core IdP authentication/governance are unchanged.

Expected system/conf framework resources from Shibboleth/OpenSAML classes are
summarized as `standard_framework_review` with MANUAL_REQUIRED, never NONE_OBSERVED
or a certificate of untouched standard configuration. Exact literal String/X509
support definitions are separate. Unknown custom classes or paths, factories/dynamic
String definitions and actual unknown selectors/functions remain UNRESOLVED. Matched
ContextCheck/SP-specific conditions are still reported without execution.
`SourceSnapshotTest`, `SourceAccessAnalyzerTest` and `ExistingCliTest` cover extraction,
namespace/expiry/provenance constraints, filter classifications, framework/custom
separation, 248-candidate round trips and all-before-write acknowledgement gates.

## Managed 2FAS-KW source contract

`--source-type auto` selects managed mode when the source registry is present.
An invalid/unreadable registry never silently degrades to generic mode. Explicit
`shibboleth` mode warns that source management intent is omitted. Bundle mode cannot
be overridden at import. Supported adapters cover registry schemas 1/2 and catalog/
access schemas 1 in the 1.3.5/1.4.0 file contract; this is not arbitrary-version support.

Registry entries must match metadata bytes/hash, entityID, ACS/certificate inventory,
the managed provider, owned XML policy/profile attributes, SP MFA/CIDRs, and actual access
policy identity/revision. Schema 1 derives access from the policy, not absent flags.
The registry records one ACS location per endpoint, so different bindings may share
the same URL. Only `acsUrls` uses duplicate-tolerant string validation and stable
deduplication; the analyzer still compares the resulting URL set with metadata.
Source JSON and metadata bytes are never rewritten. Duplicate JSON keys, identities,
and other string lists remain invalid. Regression tests cover shared locations across
bindings, mismatched URL sets, malformed values, and source/bundle byte preservation.
ACTIVE entries only are selected. Unmanaged source SPs require a separate workflow.
Nonempty RP overrides and unknown resource conditions retain the managed analysis hold.
Source files are reread for hashes; runtime bean activation is not statically proven.

The managed adapters are intentionally conservative about path/encoding conventions.
Management files must be contained in the source home, without symlinks. Management
properties use the existing ISO-8859-1 Java Properties contract; MFA's CLI literal
reader and runtime Properties reader must yield identical data. Duplicate properties,
continuations and ambiguous escape/non-ASCII decoding are held rather than guessed.
The initial CIDR adapter supports IPv4 only.

Jackson core 2.21.7 is the sole additional runtime dependency, packaged beside the
tool JAR, not installed into the IdP. Trees contain only maps/lists/strings/longs/
booleans. Limits include 4 MiB JSON input, depth 32 and 100000 nodes, with narrower
registry/catalog/access file limits of 2/1/1 MiB. Duplicate keys, unknown fields,
null/coercion and out-of-range revisions fail closed. Stable sorted JSON output
prevents JVM map iteration order from changing a resumed plan.

## Managed bundle and execution protocol

Generic v1 remains readable with LEGACY_UNASSESSED authorization. New generic manifest
v4 records a mandatory access assessment and hash-bound snapshot originals; v3 is also readable and managed v2 is unchanged.
Managed manifest v2 has a `2faskw` header,
global MFA comparison record and per-SP intent records in internal Base64 TSV.
It exports selected profile/approval/access intent, never raw legacy XML, credentials,
history or target revisions. Allow/deny values preserve spaces, commas and equals
characters. TXT reports remain the operator interface; they are not edited to execute.
New intents include an optional `releaseContract` with profile and additional attribute
lists. The loader validates bounds (32/256), disjoint sets, their union against the
effective attribute record, and approval dependencies against profile/access only.
Older v2 intents without this field retain their original single-set semantics.
Old held exports must be reanalyzed into a fresh directory; loading does not clear holds.
Old tools reject the new intent field rather than silently losing additional releases.

Target preparation uses CLI inventory/init/discovery/profile/MFA checks and read-only
registry/access inspection. The source and target global MFA contract must match
exactly; global policy is not copied. Target ContextCheck and the global access flag
must already be prepared when importing a policy. Resolver/mapping/ACS prerequisites,
existing names/entities and profile conflicts are checked before writes. Existing
approvals/descriptions are retained only after explicit review; release-approved is
never downgraded to internal-only. Shared new profiles are created once.

For scoped intents, `SourceAnalyzer.analyzeReleases` analyzes incoming immutable
metadata against target service resources and attribute filters without registering
or enumerating target SPs. The expected managed LocalDynamic provider must be unique,
use its standard directory, and have only known unfiltered Chaining ancestors.
The same tri-state metadata-tag evaluator applies; unresolved policies/providers stop
the plan. Target additional attribute sets must exactly match the source contract,
including rejecting unexpected releases. Common attributes need declared/observed/
conditional resolution and mapped, non-blocked discovery, not a new catalog approval.
Dynamic runtime use, user values and signature authenticity remain external checks.

`additional-release-check.txt` records evidence and missing/unexpected sets. All resolved
XML/property inputs (including custom imported files) join the baseline hashes. Checks
run at preparation, execution and before/after each CLI write. A permitted filter rewrite
must still preserve additional semantics. Failure stops later operations and retains
completed writes; keep the target isolated. No common policy is generated or modified
by the importer. Legacy intents keep their original target-check contract.

The normalized plan binds bundle hash, target real path, selected identities/names/
profiles/hashes, source intent and final reviewed approval/description contracts.
It excludes transient `exists`/`createProfile` booleans so a successful earlier phase
does not invalidate resume. Before every write the relevant target files are hashed;
only the known CLI operation's expected file changes are accepted afterward.
Unknown concurrent changes stop execution, but this is not a cross-process transaction
or shared management lock. Exclusive administration is mandatory.

```text
review target / dependencies / plan
  -> confirm isolated target and registration count
  -> attributes approve (release/access, explicit classification and purpose)
  -> shared attributes profile create
  -> add dry-run -> digest-bound add --apply -> verify
  -> access set --confirm ENTITY_ID (sets enabled=true)
  -> access disable --confirm ENTITY_ID (only if source policy is disabled)
  -> verify SP / profile / approvals / exact access values
  -> CONFIG_VERIFIED
```

Existing SPs are accepted only by a bound resume with matching ACTIVE identity, hash,
profile and MFA/CIDRs. Missing access is applied after SP verification. For a disabled
policy, an identical enabled policy is the recognized intermediate set-before-disable
state; unknown policy changes are not overwritten. Completed dependencies survive
failure. There is no automatic adopt/update/delete, bulk rollback or global MFA repair.

Managed apply requires the isolation flag plus an `ISOLATED TARGET` confirmation.
The application does not change the firewall. An operator must keep the target
unpublished even after partial failure, because registration precedes access setup.
The result explicitly leaves READY_FOR_CUTOVER and CUTOVER_VERIFIED unconfirmed.
`mfa test` is only a representative configuration probe, not exhaustive network or
user testing. Browser allow/deny/missing-attribute/MFA/NameID/trust tests gate cutover.

`ManagedSourceAnalyzerTest`, `ManagedMigrationTest` and managed entry tests cover
schema differences, source drift, strict parsing, source access wiring, intent export,
target prerequisites/conflicts, shared profiles, approval scopes, disabled policies,
partial resume and target changes. Apply is exercised against a stateful mock CLI;
real Linux sudo/IdP reload/browser qualification remains an external test obligation.
Package checks verify the dependency classpath, inventory, hashes and CycloneDX SBOM.

## Review Presentation

`ReviewPresentation` is a read-only adapter over `Analysis`/`Candidate`. It renders
localized totals, unique-per-code overlapping hold counts, stable candidate numbers,
primary causes plus related codes, and per-SP administrator checks. Checks name the
actual source and planned attributes, original signature/validity/filter obligations,
dynamic refresh procedures, target profile/MFA/ACS prerequisites, authorization
reconstruction, and SP-side trust/NameID/encryption/browser tests. Managed policy
migration is distinguished from generic authorization that is not copied.
Validity codes are not translated into an unjustified assertion of actual expiry.
Unknown diagnostics retain their original cause, location and remedy.

`SpImportMain.select` accepts `held`, `ready`, `all` and `d NUMBER`; view changes do
not renumber candidates, change blocked state, or bypass entity selection constraints.
All-held inventories permit inspection/cancellation, not registration. Invalid detail
numbers, including integer overflow, are handled without leaving the selection loop.
The English locale translates guidance while preserving original technical evidence.

`MigrationBundle.save` also emits localized `review-checklist.txt`. The optional file
is informational, not manifest input, verification evidence or approval. Older bundles
do not require it. Console/detail guidance is regenerated from validated model data.
The canonical `MigrationBundle.detail`, fingerprint and write/approval gates are not
replaced by rendered prose; editing the TXT cannot grant approval or unblock an SP.
Pre-registration review is separated from post-registration isolated qualification
before cutover. Unsigned originals need source acceptance and independent provenance
confirmation rather than a fictitious signature success.

`ReviewPresentationTest` covers reason grouping, source-specific checklists, locale,
managed/generic distinctions, stable filters, detail evidence, invalid selection,
entity constraints, all-held cancellation and non-authoritative report edits.

### Precise Hold Evidence and Compact Checklists

An analyzer instance captures a single UTC `checked_at`. Validity diagnostics retain
the existing blocking codes but distinguish missing/malformed/expired timestamps,
unsupported/nonpositive cache durations, invalid maximum intervals and exceeded
validity windows in `reason`. Evidence includes actual values, filter locations and
the computed `allowed_until` when applicable. Snapshot failures identify the failing
ancestor through SAX parent links, rather than reporting the leaf entity location.
Entity/ancestor validity facts are recorded even when extraction fails. No signature
verification or source refresh is inferred from these values.

Unsupported attribute release diagnostics name the policy, attribute and rule tree,
including known operands and references. Rendering is bounded by depth, node count
and character limits; arbitrary attribute values and element text (including scripts)
are omitted. The expression is evidence only, never evaluated or converted to ANY.
Old bundles retain their old diagnostics; absent values are not fabricated.

Overview/checklist reports print common obligations once, followed by candidate-specific
source, attributes, managed intent, refresh and authorization evidence. Blocking evidence
is visible without entering details. `d NUMBER` remains a self-contained checklist and
preserves every original diagnostic. Eligibility, selection, confirmation and isolation
gates are unchanged. Tests cover failure discrimination, ancestor locations, bundle
round-trips, bounded/redacted condition trees and non-repeated common checks.

### Cause Classification and Per-SP Migration Work

`Diagnostic.reason()` reads a bounded uppercase reason prefix from existing diagnostic
detail; it never infers a reason from legacy prose. `SourceAnalyzer.attributeHold`
preserves the blocking code and exact quoted attribute ID, policy location and bounded
condition evidence. Missing/whitespace/unsupported IDs are distinguished from recognized
value-limited or deny shapes and unresolved custom conditions. ID and condition holds
are independent when both apply. No ID normalization or release broadening is performed.

`attributeReason`/`valueLimited` recognize only conservative, namespace-qualified rule
shapes for explanatory classification. They do not evaluate values, regexes, scripts,
refs or runtime conditions and do not certify source correctness. The existing permit
evaluator and registration holds remain authoritative; value/deny conditions stay held.

`MigrationGuidance` maps blocking diagnoses into ordered work categories: source
configuration review, authoritative metadata refresh, validity/filter review, manual
attribute release migration, additional investigation, extraction/distribution review,
managed consistency, separate authorization review and target qualification. Summaries
count each candidate once per category, with explicit overlap across categories. Every
candidate retains target qualification; pending/unassessed authorization is not omitted.

`work` renders the actual candidate's entity/name, diagnostic locations and reasons;
the detailed view/report also includes condition operands and authorization evidence.
Instructions separate source corrections from manual equivalent migration, identify
shared parent-metadata dependencies and order final target/browser qualification after
earlier work. Manual release instructions cover target filter resources/shared policies,
filtered AACLI and SP-received matching/nonmatching/absent/mixed values. Authorization
tests are separate. A manual target filter does not unblock this tool: an administrator
must plan a separate existing-CLI migration if needed, without dropping source conditions.

`MigrationBundle.save` emits localized optional `migration-actions.txt`; `report` is
informational, not a plan, approval or proof of verification. Display is regenerated
from validated candidate data. Editing/removing the TXT changes neither fingerprint
nor eligibility; old bundles need neither the TXT nor invented diagnostic details.
Manifest schemas, isolation/approval/selection gates, exit codes and core IdP/DB/SP
management code are unchanged. `MigrationGuidanceTest` and `SourceAnalyzerTest` cover
classification, overlapping counts, dependency order, exact per-SP evidence, legacy
fallback, locales, round-trips and non-authoritative TXT edits.

## Deferred Attribute Configuration and Apply-Time Validity

Generic target mode accepts `--defer-attributes` as an explicit two-stage migration.
`DeferredRegistration.view` copies the canonical analysis; only candidate-local
ATTRIBUTE_CONDITION_UNSUPPORTED and ATTRIBUTE_POLICY_UNRESOLVED blocking diagnostics
become REVIEW for registration. Original severity, location, detail and remedy remain
in facts and the canonical bundle stays held. Global errors and all nonattribute holds
remain blocking. Managed bundles reject this mode rather than discard their intent.

`ExistingCli` fixes the profile to the confirmed empty builtin `none`, omits attribute
discovery/approval prompts and profile creation, and validates every deferred plan
before execution. Apply needs both registration-only/isolation flags plus typed
DEFER ATTRIBUTES ISOLATED acknowledgement, even when no source authorization hold
exists. Existing metadata review and authorization acknowledgement gates still apply.
Registration updates only normal SP metadata/name/MFA via the installed CLI.
Neither authentication/DB code nor the deployed governance CLI is changed.

Exit 11 and *_ATTRIBUTES_PENDING stages distinguish this phase from complete migration.
Private `attribute-migration-todo.txt` retains source attributes, policies, diagnostic
evidence and authorization work. Unknown policies mean the discovered set may be
incomplete. The administrator reconstructs resolver/mapping, shared approval contracts,
profiles and value/deny/authorization rules through existing procedures. Filtered AACLI,
mixed/missing/nonmatching values, received attributes, allow/deny, SP trust, NameID,
encryption and browser SSO/MFA must pass before isolation ends. `none` does not disable
shared target release policies; the isolation flag does not enforce a network boundary.

Generic deferred plan-v4 binds the mode, bundle fingerprint, target path, isolation
and review flags, selection/name/none/MFA/CIDRs/hash. Existing full-attribute plan-v3
remains unchanged. Switching modes during resume is rejected; completed SPs are verified
rather than re-added. If phase two has changed a profile, resume stops instead of resetting it.

`MetadataValidity.check` parses the frozen snapshot with SecureXml and checks exact
entity/root and validUntil on the entity and SP/RoleDescriptor metadata elements.
Preparation, postconfirmation all-selected preflight and per-SP pre-add checks use the
current time in generic and managed modes. Generic code has an injectable Clock for
deterministic elapsed-time tests. Equal-to-now is expired; malformed values fail.
Foreign extension dates are not metadata validity. Missing dates are not invented.
Extracted snapshots already cap their root to inherited aggregate expiry/cache bounds.
A trusted new export is required for expired snapshots: no URL fetching, automatic
extension or inheritance of source HTTP refresh behavior occurs.
