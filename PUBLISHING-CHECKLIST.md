# Public Publishing Checklist

Use this checklist before publishing this plugin to GitHub or an official
Shibboleth plugin distribution location.

## Repository Boundary

- Publish `remote_graphicalmatrix_src` as the repository root.
- Do not publish the parent `MFA` workspace as-is. It contains local test
  certificates, private keys, CSRs, and environment-specific notes.
- Confirm the repository does not contain:
  - `*.key`
  - private `*.pem`
  - token files
  - database password files
  - exported GraphicalMatrix CSV files
  - database dumps
  - local IdP logs

## License and Attribution

- Confirm Shinshu University has approved Apache-2.0 publication.
- Confirm the copyright owner name and year in `LICENSE` and `NOTICE`.
- Keep `THIRD-PARTY-NOTICES.md` in the source repository and release ZIPs.
- Re-run dependency review whenever dependency versions change.

## Shibboleth Plugin Release

- Replace placeholder URLs in `src/main/resources/.../plugin.properties`.
- Replace placeholder URLs in `plugin-metadata/graphicalmatrix-plugin.properties`.
- Replace `bootstrap/keys.txt` with real signing public key material.
- Sign release ZIPs and publish detached signatures and SHA-256 checksums.
- Confirm `plugin.license` points to the packaged Apache-2.0 license text.
- Confirm the supported IdP, Java, and Jetty version ranges.

## Security Defaults

- Keep the published default API setting disabled.
- Do not publish production API tokens, DB passwords, or TLS client keys.
- Consider providing separate sample files for PoC and production storage
  modes so that `plaintext` sequence storage is not copied into production.
- Review `vulnerability_analysis.md` before release and either fix or document
  accepted residual risks.

## GitHub Settings

- Enable secret scanning.
- Enable Dependabot or another dependency advisory workflow.
- Protect release tags.
- Use GitHub Releases for signed artifacts, checksums, and release notes.
- Add a security contact in the public `SECURITY.md` before release.
