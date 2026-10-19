#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${BACKUP_CLIENT_CONFIG:?Missing BACKUP_CLIENT_CONFIG}"
: "${BACKUP_DIRECTORY:?Missing BACKUP_DIRECTORY}"
: "${BACKUP_DATABASE:?Missing BACKUP_DATABASE}"
: "${BACKUP_OFFSITE_DIRECTORY:?Missing BACKUP_OFFSITE_DIRECTORY}"
[[ $BACKUP_DIRECTORY = /* && ! -L $BACKUP_DIRECTORY ]] || exit 2
mkdir -p -- "$BACKUP_DIRECTORY"
exec 9>"$BACKUP_DIRECTORY/.backup.lock"
flock -n 9 || { echo 'Backup already running.' >&2; exit 75; }
script_directory=$(cd -- "$(dirname -- "$0")" && pwd)
bash "$script_directory/backup-mysql.sh" "$BACKUP_CLIENT_CONFIG" "$BACKUP_DIRECTORY" "$BACKUP_DATABASE"
python3 "$script_directory/backup-maintenance.py" "$BACKUP_DIRECTORY" "$BACKUP_OFFSITE_DIRECTORY" \
    "$BACKUP_DATABASE" --days "${BACKUP_RETENTION_DAYS:-14}"
# A marker means dump + verified offsite copy + retention all succeeded.
date -u +%s > "$BACKUP_DIRECTORY/.last-success.partial"
mv -- "$BACKUP_DIRECTORY/.last-success.partial" "$BACKUP_DIRECTORY/.last-success"
