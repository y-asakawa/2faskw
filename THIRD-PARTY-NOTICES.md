# Third-Party Notices

This file lists third-party components used by GraphicalMatrix MFA for
Shibboleth IdP. It is provided for attribution and license compliance.

## Project License

GraphicalMatrix MFA for Shibboleth IdP is licensed under the Apache License,
Version 2.0. See `LICENSE`.

## Runtime Libraries Bundled in the Release ZIP

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| HikariCP | 6.3.0 | Apache-2.0 | JDBC connection pool. |
| PostgreSQL JDBC Driver | 42.7.11 | BSD-2-Clause | PostgreSQL JDBC driver. The JAR also includes license files for bundled SCRAM/Stringprep components under `META-INF/licenses/`. |
| ZXing core | 3.5.3 | Apache-2.0 | QR code generation for TOTP enrollment. |

## Provided by the Shibboleth IdP Runtime

These dependencies are declared with Maven `provided` scope and are not
bundled in the GraphicalMatrix plugin ZIP:

| Component | Version Used for Compile | License |
| --- | --- | --- |
| Shibboleth IdP APIs | 5.2.2 | Apache-2.0 |
| OpenSAML profile API | 5.2.2 | Apache-2.0 |
| Shibboleth shared support | 9.2.2 | Apache-2.0 |
| Shibboleth TOTP plugin implementation | 2.3.1 | Apache-2.0 |
| Jakarta Servlet API | 6.0.0 | EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 |
| SLF4J API | 2.0.13 | MIT |

## Source References

- Shibboleth IdP and OpenSAML: https://shibboleth.atlassian.net/
- PostgreSQL JDBC Driver license: https://jdbc.postgresql.org/license/
- HikariCP license: https://github.com/brettwooldridge/HikariCP/blob/dev/LICENSE
- ZXing license: https://github.com/zxing/zxing/blob/master/LICENSE
- Jakarta Servlet API metadata: https://central.sonatype.com/artifact/jakarta.servlet/jakarta.servlet-api/6.0.0
- SLF4J license: https://www.slf4j.org/license.html

## Graphical and Template Assets

The SVG graphical tiles, HTML templates, CSS, shell scripts, and documentation
in this repository are part of GraphicalMatrix MFA for Shibboleth IdP unless
otherwise noted, and are licensed under the project license.
