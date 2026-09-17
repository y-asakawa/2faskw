# Design Sources

This file records public materials that were actually reviewed in connection with
2FAS-KW design or later technical review.

A source appearing here means that it was reviewed; it does **not** mean that source
code or protected expression was copied from it.

For historical work, do not add a source merely because it would have been relevant.
If contemporaneous use cannot be established, label the entry as a **later review**.

## Source register

| Date reviewed | Area | Public source | Purpose / relevance | Usage status |
| --- | --- | --- | --- | --- |
| 2026/08 | GraphicalMatrix / patent review | [JP5705165B2](https://patents.google.com/patent/JP5705165B2/en) | Compare authentication patent claims with GraphicalMatrix v1.3.4 implementation | Later technical review |
| 2026/08 | GraphicalMatrix / patent review | [JP5705167B2](https://patents.google.com/patent/JP5705167B2/en) | Compare authentication auxiliary-information/order-relation claims with GraphicalMatrix v1.3.4 | Later technical review |
| 2026/08 | Graphical authentication / prior art | [JP2005004333A](https://patents.google.com/patent/JP2005004333A/en) | Review registered images, dummy images, ordered selection and random arrangement | Later technical review |
| 2026/08 | Graphical authentication / prior art | [JP4327441B2](https://patents.google.com/patent/JP4327441B2/en) | Review image authentication and random display | Later technical review |
| 2026/08 | Graphical authentication / prior art | [JP4648420B2](https://patents.google.com/patent/JP4648420B2/en) | Review registered/dummy-image authentication | Later technical review |
| 2026/08 | Graphical authentication / prior art | [JP6058990B2](https://patents.google.com/patent/JP6058990B2/en) | Review image-authentication prior art | Later technical review |
| 2026/08 | Graphical authentication / prior art | [JP6674683B2](https://patents.google.com/patent/JP6674683B2/en) | Review random display and sequential input | Later technical review |
| 2026/08 | Graphical authentication / prior art | [JP2014021779A](https://patents.google.com/patent/JP2014021779A/ja) | Review image/group IDs and hash-related authentication representations | Later technical review |
| 2026/08 | Authentication server | [JP6566644B2](https://patents.google.com/patent/JP6566644B2/en) | Review server authentication using group/ID sequence concepts | Later technical review |
| 2026/08 | Dynamic graphical password | [JP6701359B2](https://patents.google.com/patent/JP6701359B2/en) | Review dynamic graphical-password/network-authentication architecture | Later technical review |
| 2026/08 | Image authentication | [JP2025126485A (特開2025-126485)](https://patents.google.com/patent/JP2025126485A/en) | Monitor recently published image rearrangement/authentication application | Later technical review |
| 2026/08 | Overseas patent family | [FR3086775B1](https://patents.google.com/patent/FR3086775B1/en) / [EP3633530B1](https://patents.google.com/patent/EP3633530B1/en) / [US11468157B2](https://patents.google.com/patent/US11468157B2/en) | Review image-group selection, randomized arrangement and image-sequence authentication | Later technical review |

## Public standards and OSS sources

Add standards, specifications, Shibboleth documentation, WebAuthn specifications,
TOTP specifications, libraries, papers, or other public sources here **when their
actual use can be tied to a design or implementation decision**.

Suggested entry format:

```text
Date reviewed:
Area:
Source:
Public location:
What was learned/used:
Related issue/PR/commit:
Related design decision:
```

Do not backfill unknown historical sources merely to make the development history
look complete.
