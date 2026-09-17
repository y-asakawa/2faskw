## Summary

Describe the change and why it is needed.

## Design provenance

For changes affecting authentication, credential storage, protocol behavior, or
security-sensitive logic:

- [ ] The implementation basis is documented in the issue/PR or an existing design
  record.
- [ ] Newly used public specifications, standards, papers, patents, or OSS
  documentation are recorded in
  `docs/development-history/DESIGN-SOURCES.md` where appropriate.
- [ ] No non-public or confidential material is being used as an implementation
  specification.
- [ ] Significant architectural/security decisions are recorded or updated in
  `docs/development-history/DESIGN-DECISIONS.md`.

Do not check an item merely as a declaration when it is not applicable; explain
unusual provenance in the PR discussion.

## GraphicalMatrix / patent-review impact

**PATENT-REVIEW impact:** `YES / NO / N/A`

Use `YES` when the change affects, for example:

- image grouping or state classification;
- image selection semantics or display randomization;
- ordered/unordered credential semantics;
- position/coordinate information used as a credential element;
- authentication auxiliary information;
- sequence canonicalization;
- reference-value/HMAC generation;
- GraphicalMatrix credential database schema;
- server-side verification conditions.

If `YES`, describe the review performed and update `docs/PATENT-REVIEW.md` or the
relevant internal claim chart when necessary.

## Security impact

Describe changes to authentication, credential confidentiality/integrity, lockout,
session handling, or cryptographic processing.

## Testing

Describe tests performed and relevant results.

## Traceability

- Related issue:
- Design decision:
- Public source(s):
- Relevant commit(s):
