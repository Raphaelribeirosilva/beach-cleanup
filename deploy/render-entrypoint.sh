#!/bin/sh
set -eu
umask 077
# This store contains only the public project CA, never a private key.
test -r /etc/secrets/aiven-ca.pem || { echo 'Missing Aiven CA secret file' >&2; exit 1; }
store_dir=$(mktemp -d /tmp/elo-trust.XXXXXX)
keytool -importcert -noprompt -alias aiven-project-ca -file /etc/secrets/aiven-ca.pem \
  -keystore "$store_dir/aiven.p12" -storetype PKCS12 -storepass changeit >/dev/null
export DB_TRUSTSTORE_URL="file:$store_dir/aiven.p12"
exec java -jar /app/app.jar
