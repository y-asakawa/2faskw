# Build 2FAS-KW from Source

## Build

```bash
mvn -B -ntp clean package
```

This builds the plugin JAR and copies runtime dependencies under `target/`.

## Release ZIPs

Official release ZIPs are published separately from source builds.
Artifacts built locally from source are not official release packages unless
they are produced and signed by the maintainer release process.

Expected release output names:

```text
target/plugin-dist/2faskw-idp-plugin-1.0.1.zip
target/admin-dist/2faskw-admin-tools-1.0.1.zip
```

Release ZIPs should include:

```text
LICENSE
NOTICE
THIRD-PARTY-NOTICES.md
```
