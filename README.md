# 2FAS-KW for Shibboleth IdP

2FAS-KW is a Shibboleth IdP 5 plugin that adds an GraphicalMatrix graphical-sequence
second factor after password authentication. It can route users to one of
three MFA methods based on database settings:

- GraphicalMatrix
- TOTP
- WebAuthn

The package also includes self-service GraphicalMatrix sequence change screens,
management CLI tools, optional provisioning API endpoints, PostgreSQL-backed
storage, and example Shibboleth configuration files.

## Status

This repository is being prepared for public release and official plugin
distribution. Before publishing a public release, complete
`PUBLISHING-CHECKLIST.md`, replace placeholder download URLs, set the final
security contact, and publish signed release artifacts.

Do not publish the parent `MFA` workspace. Use this directory
(`remote_graphicalmatrix_src`) as the repository root.

## Requirements

- Shibboleth IdP 5.2 or later
- Java 21
- Jetty 12 runtime for IdP 5
- PostgreSQL for production deployments
- Optional: Shibboleth TOTP plugin
- Optional: Shibboleth WebAuthn plugin

H2-compatible defaults may exist for local or PoC workflows, but production
deployments should use PostgreSQL and protected sequence/TOTP seed storage.

## Documentation

- Installation and operation: `docs/README.md`
- IdP installation: `docs/INSTALL.md`
- Security guide: `docs/SECURITY.md`
- Security checklist: `docs/SECURITY-CHECKLIST.md`
- Build from source: `docs/build.md`
- Admin tools: `docs/ADMIN-TOOLS.md`
- Configuration reference: `docs/CONFIG-REFERENCE.md`
- Publishing checklist: `PUBLISHING-CHECKLIST.md`

## Security Defaults

The management API is intended for trusted provisioning tools only. Release
packages set it disabled by default:

```properties
graphicalmatrix.api.enabled = false
```

If the API is enabled, restrict it with HTTPS, firewall or load-balancer
rules, `graphicalmatrix.api.allowedCidrs`, and a bearer token file readable only
by the IdP runtime user.

For production, avoid plaintext GraphicalMatrix sequence storage. Use `hash`,
`keyword`, or `aes-gcm` for sequences, and use recoverable protected storage
such as `aes-gcm` or `keyword` for TOTP seeds.

## License

2FAS-KW for Shibboleth IdP is licensed under the Apache License,
Version 2.0. See `LICENSE`.

Third-party license and attribution details are listed in
`THIRD-PARTY-NOTICES.md`.

## Security Reporting

Do not open public GitHub issues for suspected vulnerabilities. Follow
`SECURITY.md` and replace the placeholder contact with the official security
contact before public release.

## **Releases**: https://github.com/y-asakawa/2faskw/releases/

## Screenshots

<p>
  <img src="MFA01.png" alt="2FAS-KW screenshot 1" width="32%">
  <img src="MFA02.png" alt="2FAS-KW screenshot 2" width="32%">
  <img src="MFA03.png" alt="2FAS-KW screenshot 3" width="32%">
</p>
