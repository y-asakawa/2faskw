# GraphicalMatrix Prior-Art Review

- **Scope:** Technical prior-art register for GraphicalMatrix
- **Baseline reviewed:** 2FAS-KW v1.3.4
- **Review date:** 2026/08

This document records public patent literature identified during the technical
review. It is not a complete patent search and does not determine patent validity,
infringement, or legal FTO.

## Relevant documents

| Document | Relevant concept | Review treatment |
| --- | --- | --- |
| [JP2005004333A](https://patents.google.com/patent/JP2005004333A/en) | Registered authentication images plus dummy images; registered-order selection; random arrangement | Important prior-art reference |
| [JP4327441B2](https://patents.google.com/patent/JP4327441B2/en) | Image personal authentication; password/decoy images; random display positions | Prior-art reference |
| [JP4648420B2](https://patents.google.com/patent/JP4648420B2/en) | Selection of registered images among multiple images | Prior-art reference |
| [JP6058990B2](https://patents.google.com/patent/JP6058990B2/en) | Image authentication; discussion of correct images among random images | Prior-art reference |
| [JP6674683B2](https://patents.google.com/patent/JP6674683B2/en) | Randomly arranged numeric images and sequential selection | Prior-art reference |
| [JP5705165B2](https://patents.google.com/patent/JP5705165B2/en) | Group configuration, multiple states, least-populated-state authentication; dependent one-way processing | Detailed implementation/claim comparison completed |
| [JP5705167B2](https://patents.google.com/patent/JP5705167B2/en) | Authentication auxiliary information, authentication information, operation-order relationship; dependent one-way processing/random display | Detailed implementation/claim comparison completed |
| [JP2014021779A](https://patents.google.com/patent/JP2014021779A/ja) | Image IDs, hash-related authentication representation, group architecture | Important prior-art reference |
| [JP6566644B2](https://patents.google.com/patent/JP6566644B2/en) | Authentication server; password/group-ID sequence | Continuing review |
| [JP6701359B2](https://patents.google.com/patent/JP6701359B2/en) | Dynamic graphical-password network authentication | Continuing review |
| [JP2025126485A (特開2025-126485)](https://patents.google.com/patent/JP2025126485A/en) | Base image, random seed, rearrangement program and restoration | Monitor application status |
| [FR3086775B1](https://patents.google.com/patent/FR3086775B1/en) / [EP3633530B1](https://patents.google.com/patent/EP3633530B1/en) / [US11468157B2](https://patents.google.com/patent/US11468157B2/en) | Group selection plus randomized image arrangement and image sequence | Overseas-family review |

## Current technical observation

The reviewed literature demonstrates that individual concepts such as graphical
passwords, image selection, random arrangement, ordered input, image identifiers,
server-side authentication, and one-way processing existed independently in prior
public literature.

The v1.3.4 GraphicalMatrix implementation reviewed in 2026/08 uses the following
combination:

```text
randomized display
      +
ordered image-ID selection
      +
server-side selection validation
      +
ordered canonical image-ID sequence
      +
salted, pepper-keyed HMAC-SHA256 reference
      +
server-side recomputation and digest comparison
```

The review has not established that this combination is novel or patentable.
Likewise, absence of an identified claim in this review is not evidence that no
relevant patent exists.

## Detailed reviews

Detailed technical claim charts have been prepared separately for:

- JP5705165B2
- JP5705167B2

For public repository use, [PATENT-REVIEW.md](../PATENT-REVIEW.md) remains the primary
public summary. Detailed claim charts may be maintained as internal engineering
review material.

## Update triggers

Review this register when:

- GraphicalMatrix credential semantics change;
- image grouping or state classification is introduced;
- auxiliary authentication data is introduced;
- position/coordinate relationships become credential elements;
- a monitored application is granted or materially amended;
- a new relevant Japanese patent/application is identified.
