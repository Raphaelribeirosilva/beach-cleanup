#!/usr/bin/env bash
# Isolated database smoke test. Never accepts a source/live target database name.
set -euo pipefail
umask 077
[[ $# == 3 && $1 = /* && $2 = /* && $3 = /* ]] || exit 2
client_config=$1
dump=$2
reports=$3
[[ -f $client_config && ! -L $client_config && $(stat -c '%a' -- "$client_config") == 600 ]] || exit 2
[[ -f $dump && ! -L $dump && -f $dump.sha256 && ! -L $dump.sha256 && ! -L $reports ]] || exit 2
read -r expected reference < "$dump.sha256"
[[ $expected =~ ^[a-f0-9]{64}$ && $reference == "$(basename -- "$dump")" && $(wc -l < "$dump.sha256") == 1 ]] || exit 2
(cd -- "$(dirname -- "$dump")" && sha256sum --check --status -- "$(basename -- "$dump").sha256")
mysql_options=("--defaults-file=$client_config" --batch --skip-column-names --raw)
[[ $(mysql "${mysql_options[@]}" --execute='SELECT @@partial_revokes') == 0 ]] || {
    echo 'Restore grant pattern requires partial_revokes=0; adapt grants before running.' >&2; exit 2;
}
work=$(mktemp -d)
suffix=$(basename -- "$work")
suffix=${suffix##*.}
[[ $suffix =~ ^[A-Za-z0-9]+$ ]] || exit 2
database="beachcleanup_restorecheck_$(date -u +%Y%m%dT%H%M%SZ)_$suffix"
created=false
cleanup() {
    status=$?
    if [[ $created == true && $database =~ ^beachcleanup_restorecheck_[0-9]{8}T[0-9]{6}Z_[A-Za-z0-9]+$ ]]; then
        mysql "${mysql_options[@]}" --execute="DROP DATABASE \`$database\`" >/dev/null || {
            echo 'Temporary restore database needs operator cleanup.' >&2; status=1;
        }
    fi
    rm -f -- "$work/tables" "$work/counts"
    rmdir -- "$work"
    exit "$status"
}
trap cleanup EXIT
begin=$SECONDS
mysql "${mysql_options[@]}" --execute="CREATE DATABASE \`$database\` CHARACTER SET utf8mb4"
created=true
mysql "${mysql_options[@]}" --binary-mode=1 "$database" < "$dump"
[[ $(mysql "${mysql_options[@]}" "$database" --execute='SELECT COUNT(*) FROM flyway_schema_history WHERE success=0') == 0 ]] || exit 1
[[ $(mysql "${mysql_options[@]}" "$database" --execute='SELECT COUNT(*) FROM tb_trash_collection c LEFT JOIN tb_user u ON u.id=c.volunteer_id LEFT JOIN tb_cleanup_event e ON e.id=c.event_id WHERE (c.volunteer_id IS NOT NULL AND u.id IS NULL) OR (c.event_id IS NOT NULL AND e.id IS NULL)') == 0 ]] || exit 1
mysql "${mysql_options[@]}" "$database" --execute='SHOW TABLES' > "$work/tables"
while IFS= read -r table; do
    [[ $table =~ ^[A-Za-z0-9_]+$ ]] || exit 1
    count=$(mysql "${mysql_options[@]}" "$database" --execute="SELECT COUNT(*) FROM \`$table\`")
    [[ $count =~ ^[0-9]+$ ]] || exit 1
    printf '%s\t%s\n' "$table" "$count" >> "$work/counts"
done < "$work/tables"
# An old backup must not resurrect sessions or single-use account links.
mysql "${mysql_options[@]}" "$database" --execute='START TRANSACTION; UPDATE tb_auth_session SET revoked_at=UTC_TIMESTAMP(6) WHERE revoked_at IS NULL; UPDATE tb_account_action_token SET consumed=1; UPDATE tb_user SET credential_version=credential_version+1; COMMIT;'
mkdir -p -- "$reports"
python3 - "$reports" "$work/counts" "$expected" "$((SECONDS-begin))" <<'PY'
import datetime as dt, json, os, pathlib, sys, tempfile
report = {"timestamp": dt.datetime.now(dt.timezone.utc).isoformat(), "dumpSha256": sys.argv[3],
          "restoreSeconds": int(sys.argv[4]), "databaseSmoke": "passed", "sessionsQuarantined": True,
          "applicationAcceptance": "pending", "tables": dict(line.strip().split("\t") for line in pathlib.Path(sys.argv[2]).read_text().splitlines())}
fd, name = tempfile.mkstemp(prefix="restore-", suffix=".json", dir=sys.argv[1])
with os.fdopen(fd,"w",encoding="utf-8") as output: json.dump(report,output,indent=2)
print("Isolated database restore passed; application acceptance is a separate check. Report: " + name)
PY
