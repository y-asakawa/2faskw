# Build 2FAS-KW from Source

## Build

Release/package builds use `version.ini` as the authoritative version source.

```ini
VERSION=1.0.1
ARTIFACT_ID=2faskw-idp-plugin
ADMIN_ARTIFACT_ID=2faskw-admin-tools
```

`scripts/build-plugin-package.sh` reads `version.ini`, passes the version to
Maven as `-Drevision`, and renders plugin metadata, OpenAPI, and packaged docs
with the same version.

For direct Maven builds, `pom.xml` includes a fallback `revision` value, but
release artifacts should be built through `scripts/build-plugin-package.sh`.

```bash
mvn -B -ntp clean package
```

This builds the plugin JAR and copies runtime dependencies under `target/`.

## Release ZIPs

Build the plugin and admin-tool release ZIPs:

```bash
./scripts/build-plugin-package.sh
```

The script runs `mvn -B -ntp clean package` first, then assembles the release
directories and ZIP files.

Artifacts built locally from source are not official release packages unless
they are produced and signed by the maintainer release process.

Expected release output names:

```text
target/plugin-dist/2faskw-idp-plugin-<VERSION>.zip
target/admin-dist/2faskw-admin-tools-<VERSION>.zip
```

Release ZIPs should include:

```text
LICENSE
NOTICE
THIRD-PARTY-NOTICES.md
```
