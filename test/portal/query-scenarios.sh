#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PROGRAM="$ROOT/resources/com/bbh/config/PortalConfigQuery.java"
KEY="5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001"
UNKNOWN="5e0f0002-8c2b-4f6a-9d3e-1a7b2c4d0000"
PASSWORD="s3cret-Reader-pw"
failures=0

mkdir -p "$WORK/driver/META-INF/services"
echo FakeOracleDriver > "$WORK/driver/META-INF/services/java.sql.Driver"
if ! javac --release 11 -Xlint:all -Werror -d "$WORK/driver" "$ROOT/test/portal/FakeOracleDriver.java" > "$WORK/javac.log" 2>&1; then
  grep -v JAVA_TOOL_OPTIONS "$WORK/javac.log"
  echo "FAIL  the fake Oracle driver compiles"
  exit 1
fi

query() {
  rm -f "$WORK/out.json"
  env -u JAVA_TOOL_OPTIONS DSO_PORTAL_DB_URL="jdbc:fake:$1" DSO_PORTAL_DB_USER=DSO_LIBRARY DSO_PORTAL_DB_PASSWORD="${3-$PASSWORD}" \
    DSO_PORTAL_DB_SCHEMA="${4-DSO_PORTAL}" DSO_PORTAL_KEYS="$2" DSO_PORTAL_OUTPUT="$WORK/out.json" \
    java -Duser.timezone=UTC -cp "$WORK/driver" "$PROGRAM" > "$WORK/stdout.log" 2>&1
  echo "exit $?" >> "$WORK/stdout.log"
  cat "$WORK/out.json" 2>/dev/null
}

check() {
  if [ "$2" = "$3" ]; then
    echo "PASS  $1"
  else
    failures=$((failures + 1))
    echo "FAIL  $1"
    echo "      expected: $3"
    echo "      actual:   $2"
    sed 's/^/      /' "$WORK/stdout.log"
  fi
}

SHA="$(printf '{"keyStatus":"ACTIVE","renderedAt":"2026-10-06T08:14:19.475Z","config":{"projects":{"gui":{"site":"Z\303\274rich\\/Basel","tab":"a\tb"}}}}' | sha256sum | cut -c1-16)"
DOCUMENT='{"keyStatus":"ACTIVE","renderedAt":"2026-10-06T08:14:19.475Z","config":{"projects":{"gui":{"site":"Z\u00fcrich/Basel","tab":"a\u0009b"}}},"sha256":"'"$SHA"'"}'

check "one result per key in key order, escaped to ASCII without \\/, with the sha256 of the UTF-8 text, null for a key the portal never issued" \
  "$(query ok "$KEY,$UNKNOWN")" '{"results":['"$DOCUMENT"',null]}'
check "a TCPS URL connects without the native encryption properties" \
  "$(query ok-tcps "$KEY")" '{"results":['"$DOCUMENT"']}'
check "a password that expires soon (ORA-28098) adds the warning to a success" \
  "$(query warn "$KEY")" '{"results":['"$DOCUMENT"'],"warning":"password expires soon"}'
check "a wrong password is an auth error, reported after one attempt with the first line of the message" \
  "$(query auth "$KEY")" '{"error":"auth","message":"ORA-01017: invalid credential or not authorized; logon denied"}'
check "a locked account is a locked error" \
  "$(query locked "$KEY")" '{"error":"locked","message":"ORA-28000: The account is locked."}'
SECONDS=0
network="$(query network "$KEY")"
waited=$SECONDS
check "a network error is retried twice, after 5 and 15 seconds" \
  "$network $([ "$waited" -ge 20 ] && echo waited)" \
  '{"error":"network","message":"ORA-17002: IO Error: The Network Adapter could not establish the connection (after 3 attempts)"} waited'
check "a missing grant is a grant error and the message holds neither the key nor the password" \
  "$(query grant "$KEY")" '{"error":"grant","message":"ORA-00942: table or view does not exist (*** as ***)"}'
check "a schema that is not an Oracle name is refused before connecting" \
  "$(query ok "$KEY" "$PASSWORD" 'DSO_PORTAL.X; --')" '{"error":"config","message":"DSO_PORTAL_DB_SCHEMA is not an Oracle schema name"}'
check "a missing password is refused before connecting" \
  "$(query ok "$KEY" '')" '{"error":"config","message":"DSO_PORTAL_DB_URL, DSO_PORTAL_DB_USER, DSO_PORTAL_DB_PASSWORD and DSO_PORTAL_KEYS must be set"}'

(cd "$WORK/driver" && env -u JAVA_TOOL_OPTIONS jar cf "$WORK/fake-driver.jar" .)
sed -n "/return '''#!/,/^'''$/p" "$ROOT/src/com/bbh/config/PortalConfigReader.groovy" | sed -e "1s/^.*return '''//" -e '$d' > "$WORK/launch.sh"
DRIVER_SHA="$(sha256sum "$WORK/fake-driver.jar" | cut -d' ' -f1)"
mkdir -p "$WORK/jre"
printf '#!/bin/sh\necho java.base@21\n' > "$WORK/jre/java"
chmod +x "$WORK/jre/java"

launch() {
  rm -rf "$WORK/launch"
  mkdir -p "$WORK/launch"
  cp "$PROGRAM" "$WORK/launch/"
  env JAVA_TOOL_OPTIONS=-Dprobe=1 DSO_PORTAL_JAVA="${2-}" DSO_PORTAL_DIR="$WORK/launch" DSO_PORTAL_OUTPUT="$WORK/launch/result.json" \
    DSO_PORTAL_DB_URL=jdbc:fake:ok DSO_PORTAL_DB_USER=DSO_LIBRARY DSO_PORTAL_DB_PASSWORD="$PASSWORD" DSO_PORTAL_DB_SCHEMA=DSO_PORTAL \
    DSO_PORTAL_KEYS="$KEY" DSO_PORTAL_JDBC_DRIVER_URL="file://$WORK/fake-driver.jar" DSO_PORTAL_JDBC_DRIVER_SHA256="$1" \
    sh "$WORK/launch.sh" > "$WORK/stdout.log" 2>&1
  echo "exit $?" >> "$WORK/stdout.log"
  cat "$WORK/launch/result.json" 2>/dev/null
}

check "the bootstrap script of PortalConfigReader downloads the driver, checks its SHA-256 and runs the program without JAVA_TOOL_OPTIONS" \
  "$(launch "$DRIVER_SHA") $(stat -c %a "$WORK/launch") $(grep -c 'Picked up' "$WORK/stdout.log")" '{"results":['"$DOCUMENT"']} 700 0'
check "the bootstrap script refuses a driver whose SHA-256 differs" \
  "$(launch "$(printf %064d 0)")" '{"error":"driver","message":"driver checksum mismatch"}'
check "the bootstrap script refuses a java without jdk.compiler" \
  "$(launch "$DRIVER_SHA" "$WORK/jre/java")" '{"error":"config","message":"needs a JDK 11+ with jdk.compiler: set DSO_PORTAL_JAVA"}'

[ "$failures" -eq 0 ] && echo "ALL PORTAL QUERY SCENARIOS PASSED" || echo "$failures PORTAL QUERY SCENARIO CHECK(S) FAILED"
[ "$failures" -eq 0 ]
