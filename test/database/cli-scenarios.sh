#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOOLS="${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}"
DB="$TOOLS/database"
for need in "$DB/liquibase-4.33.0/liquibase" "$DB/liquibase-5.0.4/liquibase" "$DB/liquibase-5.0.4.tar.gz" "$DB/utPLSQL-cli/bin/utplsql" "$DB/ojdbc11.jar" "$TOOLS/jdk-21/bin/java"; do
  if [ ! -f "$need" ]; then
    echo "DATABASE CLI CHECK SKIPPED - put Liquibase 4.33.0 and 5.0.4, utPLSQL-cli and ojdbc11 into $DB to run it (see test/README.md)"
    exit 0
  fi
done
if [ -z "${CP:-}" ] || [ ! -d "${BUILD:-}/harness" ]; then
  echo "FAIL  the real CLI check needs CP and BUILD of test/run-all.sh, which compiles the harness"
  exit 1
fi
groovy -cp "$CP:$BUILD/harness" "$ROOT/test/database/CliScenarios.groovy" "$ROOT" "$DB" "$TOOLS/jdk-21" 2>&1 | grep -v WARNING
exit "${PIPESTATUS[0]}"
