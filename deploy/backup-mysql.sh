#!/usr/bin/env bash
# Usage: bash backup-mysql.sh /etc/beachcleanup/backup.cnf /var/backups/beachcleanup beachcleanup_staging
set -euo pipefail
umask 077

if [[ $# != 3 || ! $1 = /* || ! $2 = /* || ! $3 =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo 'Expected: absolute client config, absolute backup directory, database name.' >&2
    exit 2
fi
client_config=$1
backup_directory=$2
database=$3
[[ -f "$client_config" && -r "$client_config" ]] || { echo 'Client config unavailable.' >&2; exit 2; }
[[ ! -L "$client_config" && $(stat -c '%a' -- "$client_config") == 600 ]] || {
    echo 'Client config must be a regular private file (0600).' >&2; exit 2;
}
[[ ! -L "$backup_directory" ]] || { echo 'Backup directory must not be a symlink.' >&2; exit 2; }
mkdir -p -- "$backup_directory"
backup_file=$(mktemp "$backup_directory/$database-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX.sql")
complete=false
cleanup() {
    if [[ $complete != true ]]; then
        rm -f -- "$backup_file" "$backup_file.sha256"
    fi
}
trap cleanup EXIT

# --defaults-file must be the first option. No password in argv or repository.
# Do not run DDL/migrations while this transactional dump is being produced.
mysqldump --defaults-file="$client_config" --single-transaction --quick \
    --no-tablespaces --set-gtid-purged=OFF --routines --events --triggers \
    --result-file="$backup_file" "$database"
[[ -s "$backup_file" ]] || { echo 'Empty backup rejected.' >&2; exit 1; }
# Relative checksum names remain verifiable after copying the pair to another host.
(cd -- "$backup_directory" && sha256sum -- "$(basename -- "$backup_file")" > "$(basename -- "$backup_file").sha256")
complete=true
printf 'Backup created; restore still needs verification: %s\n' "$backup_file"
