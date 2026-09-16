# Development History and Design Provenance

This directory records the design provenance of 2FAS-KW.

The purpose is to make important design decisions traceable from publicly available
technical information through issues, pull requests, commits, implementation, and
review records.

This is an engineering record. It is not a legal opinion, a patent non-infringement
guarantee, or a representation that every historical design decision can be
reconstructed completely.

## Recording policy

Records should distinguish:

- facts that can be confirmed from source code, configuration, release artifacts,
  Git history, or public documents;
- decisions and reasons recorded at the time of a change;
- later technical reviews of an existing implementation.

Do not reconstruct historical facts from memory as if they were contemporaneous
records. When a historical source or reason cannot be verified, record it as unknown
or as a later review.

Only materials actually used or reviewed should be listed as design sources.

## Files

- [DESIGN-SOURCES.md](./DESIGN-SOURCES.md): public specifications, standards, OSS
  documentation, patents, papers, and other public sources actually reviewed.
- [DESIGN-DECISIONS.md](./DESIGN-DECISIONS.md): important architectural and security
  decisions and their implementation evidence.
- [PRIOR-ART.md](./PRIOR-ART.md): prior-art and patent-document review relevant to
  GraphicalMatrix.
- [PATENT-REVIEW.md](../PATENT-REVIEW.md): public patent-review summary.

## Traceability model

```text
Public source / existing project requirement
                 |
                 v
          Design decision
                 |
                 v
          Issue / discussion
                 |
                 v
              PR
                 |
                 v
             Commit
                 |
                 v
       Source / configuration
                 |
                 v
       Review / release artifact
```

For new changes, link the issue, PR, commit, ADR/design decision, and source references
where practical.

## Baseline

The first implementation baseline recorded by this documentation is:

- Product: 2FAS-KW for Shibboleth IdP
- Version reviewed: v1.3.4
- Artifact: `2faskw-idp-plugin-1.3.4.jar`
- SHA-256: `97deda46158cedc9e2492c1c6e43520464c58071b922b3d486f09c0793de65e4`
- Review date: 2026/08

The supplied v1.3.4 distribution did not establish the corresponding Git commit SHA.
Therefore the artifact hash, rather than an inferred commit, is used as the evidence
anchor for this baseline.
