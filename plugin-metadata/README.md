# Plugin Metadata

This directory contains metadata for the GraphicalMatrix MFA plugin package.

Files generated in the distribution package:

- `graphicalmatrix-plugin.properties`
- `PACKAGE-CONTENTS.txt`
- `PACKAGE-MANIFEST.sha256`

`graphicalmatrix-plugin.properties` is compatibility/distribution metadata.
The current package version is `1.0.1`.
The build also produces `graphicalmatrix-admin-tools-1.0.1.zip` for standalone CLI installation.

Before external distribution:

1. Replace placeholder download URLs with production HTTPS URLs.
2. Build the release package.
3. Publish the ZIP, detached signature, checksum, and public key material.
4. Replace `bootstrap/keys.txt` with real signing public key material.
5. Confirm the supported IdP and Java version range.
6. Re-run package verification.
