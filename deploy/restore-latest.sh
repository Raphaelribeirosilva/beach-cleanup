#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${BACKUP_DIRECTORY:?Missing BACKUP_DIRECTORY}"
: "${BACKUP_DATABASE:?Missing BACKUP_DATABASE}"
: "${RESTORE_CLIENT_CONFIG:?Missing RESTORE_CLIENT_CONFIG}"
: "${RESTORE_REPORT_DIRECTORY:?Missing RESTORE_REPORT_DIRECTORY}"
[[ $BACKUP_DIRECTORY = /* && ! -L $BACKUP_DIRECTORY && $BACKUP_DATABASE =~ ^[A-Za-z0-9_]+$ ]] || exit 2
latest=''
for candidate in "$BACKUP_DIRECTORY/$BACKUP_DATABASE-"*.sql; do
    [[ -f $candidate && ! -L $candidate && -f $candidate.sha256 ]] || continue
    name=$(basename -- "$candidate")
    [[ $name =~ ^${BACKUP_DATABASE}-[0-9]{8}T[0-9]{6}Z-[A-Za-z0-9]{6}\.sql$ ]] || continue
    [[ $candidate > $latest ]] && latest=$candidate
done
[[ -n $latest ]] || { echo 'No complete backup for restore drill.' >&2; exit 1; }
script_directory=$(cd -- "$(dirname -- "$0")" && pwd)
bash "$script_directory/restore-mysql.sh" "$RESTORE_CLIENT_CONFIG" "$latest" "$RESTORE_REPORT_DIRECTORY"
