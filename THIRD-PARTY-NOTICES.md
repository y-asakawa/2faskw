# Third-Party Notices

This file lists third-party components used by 2FAS-KW for
Shibboleth IdP. It is provided for attribution and license compliance.

## Project License

2FAS-KW for Shibboleth IdP is licensed under the Apache License,
Version 2.0. See `LICENSE`.

## Runtime Libraries Bundled in the Plugin and Admin Tools ZIP Files

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| HikariCP | 7.1.0 | Apache-2.0 | JDBC connection pool. |
| PostgreSQL JDBC Driver | 42.7.13 | BSD-2-Clause | PostgreSQL JDBC driver. The JAR also includes license files for bundled SCRAM/Stringprep components under `META-INF/licenses/`. |
| ZXing core | 3.5.4 | Apache-2.0 | QR code generation for TOTP enrollment. |

## Runtime Libraries Bundled in the Dashboard ZIP

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| Jackson annotations | 2.21.5 | Apache-2.0 | JSON annotations. |
| Jackson core | 2.21.5 | Apache-2.0 | JSON streaming support. |
| Jackson databind | 2.21.5 | Apache-2.0 | JSON serialization and deserialization. |
| Jackson datatype JSR310 | 2.21.5 | Apache-2.0 | Java date/time JSON support. |
| H2 Database Engine | 2.4.240 | MPL-2.0 OR EPL-1.0 | Rebuildable Dashboard search index. It is not the MFA enrollment database. |
| RE2/J | 1.8 | BSD-3-Clause | Linear-time regular expression matching for event filters. |

## Provided by the Shibboleth IdP Runtime

These dependencies are declared with Maven `provided` scope and are not
bundled in the 2FAS-KW plugin ZIP:

| Component | Version Used for Compile | License |
| --- | --- | --- |
| Shibboleth IdP APIs | 5.2.3 | Apache-2.0 |
| OpenSAML profile API | 5.2.3 | Apache-2.0 |
| Shibboleth shared support | 9.2.3 | Apache-2.0 |
| Shibboleth TOTP plugin implementation | 2.3.2 | Apache-2.0 |
| Jakarta Servlet API | 6.1.0 | EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 |
| SLF4J API | 2.0.18 | MIT |

## Source References

- Shibboleth IdP and OpenSAML: https://shibboleth.atlassian.net/
- PostgreSQL JDBC Driver license: https://jdbc.postgresql.org/license/
- HikariCP license: https://github.com/brettwooldridge/HikariCP/blob/dev/LICENSE
- ZXing license: https://github.com/zxing/zxing/blob/master/LICENSE
- Jakarta Servlet API metadata: https://central.sonatype.com/artifact/jakarta.servlet/jakarta.servlet-api/6.1.0
- SLF4J license: https://www.slf4j.org/license.html
- Jackson license: https://github.com/FasterXML/jackson/blob/2.21/LICENSE
- H2 Database Engine license: https://github.com/h2database/h2database/blob/master/LICENSE.txt
- RE2/J license: https://github.com/google/re2j/blob/master/LICENSE

## Graphical and Template Assets

The SVG graphical tiles, HTML templates, CSS, shell scripts, and documentation
in this repository are part of 2FAS-KW for Shibboleth IdP unless
otherwise noted, and are licensed under the project license.
