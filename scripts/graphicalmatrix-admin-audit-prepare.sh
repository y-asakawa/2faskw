#!/usr/bin/env bash
# Prepare the shared administrative audit sink; never truncate an existing log or replace its lock.
set -euo pipefail

DIRECTORY=/var/log/2faskw
APPLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --directory) [[ -n "${2:-}" ]] || exit 2; DIRECTORY="$2"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    -h|--help)
      printf '%s\n' 'Usage: graphicalmatrix-admin-audit-prepare.sh [--directory /var/log/2faskw] [--apply]'
      exit 0 ;;
    *) printf '%s\n' 'ERROR: unknown audit preparation option' >&2; exit 2 ;;
  esac
done
DIRECTORY="${DIRECTORY%/}"
[[ "$DIRECTORY" == /* && "$DIRECTORY" != *'/../'* && "$DIRECTORY" != */.. \
  && "$DIRECTORY" != *'/./'* && "$DIRECTORY" != */. ]] || exit 2
case "$DIRECTORY" in
  /|/var|/var/log|/etc|/opt|/usr|/usr/local|/tmp|/home|/root|/run)
    echo 'ERROR: choose a dedicated audit directory, not a shared system directory' >&2; exit 2 ;;
esac
printf 'Audit sink: %s/admin-audit.log (root:graphicalmatrix-admin 0660)\n' "$DIRECTORY"
if [[ "$APPLY" != 1 ]]; then
  printf '%s\n' 'Dry-run only. Re-run with --apply to prepare; no existing log will be truncated.'
  exit 0
fi
[[ "$(id -u)" == 0 ]] || { echo 'ERROR: preparation requires root' >&2; exit 1; }
getent group graphicalmatrix-admin >/dev/null || groupadd --system graphicalmatrix-admin

parent="$(dirname "$DIRECTORY")"
while :; do
  [[ -d "$parent" && ! -L "$parent" && "$(stat -c %u "$parent")" == 0 ]] \
    || { echo 'ERROR: audit parent must be an existing root-controlled directory without symlinks' >&2; exit 1; }
  mode="$(stat -c %a "$parent")"
  (( (8#$mode & 0022) == 0 )) \
    || { echo 'ERROR: audit ancestors must not be group/other writable; use a trusted audit directory' >&2; exit 1; }
  [[ "$parent" != / ]] || break
  parent="$(dirname "$parent")"
done
if [[ -e "$DIRECTORY" || -L "$DIRECTORY" ]]; then
  [[ -d "$DIRECTORY" && ! -L "$DIRECTORY" && "$(stat -c %u "$DIRECTORY")" == 0 ]] \
    || { echo 'ERROR: unsafe existing audit directory' >&2; exit 1; }
else
  mkdir -m 0750 "$DIRECTORY"
fi
chown root:graphicalmatrix-admin "$DIRECTORY"
chmod 0750 "$DIRECTORY"
for name in admin-audit.log admin-audit.lock; do
  file="$DIRECTORY/$name"
  if [[ -e "$file" || -L "$file" ]]; then
    [[ -f "$file" && ! -L "$file" && "$(stat -c %h "$file")" == 1 && "$(stat -c %u "$file")" == 0 ]] \
      || { echo 'ERROR: refusing linked or non-root-owned audit file' >&2; exit 1; }
  else
    (set -o noclobber; umask 077; : > "$file")
  fi
  chown root:graphicalmatrix-admin "$file"
  chmod 0660 "$file"
done
echo 'Audit sink ready. Add only trusted CLI operators/service accounts to graphicalmatrix-admin.'
echo 'Do not grant the Jetty service account membership for H2 worker execution.'
