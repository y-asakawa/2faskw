# 2FAS-KW Log Rotation Examples

Install only the files present in this distribution that apply to enabled
components. Run logrotate as root. The existing authentication, SP and CSV logs
use `copytruncate`; the administrative CLI audit MUST use rename/create instead.

| File | Log |
| --- | --- |
| `graphicalmatrix-audit` | Main GraphicalMatrix authentication audit log. |
| `graphicalmatrix-sp-management-audit` | SP management CLI and governance audit log. |
| `graphicalmatrix-access-audit` | SP attribute access-decision audit log. |
| `graphicalmatrix-csv-import` | Admin Tools CSV provisioning log. |
| `graphicalmatrix-admin-audit` | Mandatory CLI administrative audit; rename/create 0660 root:graphicalmatrix-admin with delaycompress. Never rotate admin-audit.lock or use copytruncate for this file. |

The examples do not manage Shibboleth IdP standard logs or systemd journal.
Manage those through the existing IdP logback configuration and journald
retention policy. See the published LOGROTATE.md for setup and verification.
