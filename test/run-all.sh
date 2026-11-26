#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/test/setup-tools.sh" || exit 1

export JAVA_HOME="$DEVSECOPS_JDK"
export PATH="$JAVA_HOME/bin:$TOOLS/groovy-2.4.21/bin:$PATH"
export JAVA_OPTS="${JAVA_OPTS:-} -Xss4m"
CP="$(ls "$LIBS"/*.jar | tr '\n' ':')"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
status=0

echo "=== static checks ==="
for check in "$ROOT"/test/checks/*.sh; do
  bash "$check" || status=1
done
for check in "$ROOT"/test/checks/*.groovy; do
  groovy "$check" "$ROOT" "$LIBS" 2>&1 | grep -v WARNING
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
done

echo "=== compile src, vars and ci/Jenkinsfile ==="
cat > "$BUILD/compiler-config.groovy" <<'CFG'
import org.codehaus.groovy.control.customizers.ImportCustomizer
def imports = new ImportCustomizer()
imports.addImports('com.cloudbees.groovy.cps.NonCPS')
configuration.addCompilationCustomizers(imports)
CFG
if groovyc -cp "$ROOT/test/sandbox/stub:$ROOT/src" --configscript "$BUILD/compiler-config.groovy" -d "$BUILD/src" \
     $(find "$ROOT/src" -name '*.groovy') > "$BUILD/src.log" 2>&1; then
  echo "src compiled"
else
  grep -v WARNING "$BUILD/src.log"; status=1
fi
if groovyc -cp "$ROOT/test/sandbox/stub:$BUILD/src:$ROOT/src" --configscript "$BUILD/compiler-config.groovy" -d "$BUILD/vars" \
     $(find "$ROOT/vars" -name '*.groovy') > "$BUILD/vars.log" 2>&1; then
  echo "vars compiled"
else
  grep -v WARNING "$BUILD/vars.log"; status=1
fi
if groovyc -d "$BUILD/ci" "$ROOT/ci/Jenkinsfile" > "$BUILD/ci.log" 2>&1; then
  echo "ci/Jenkinsfile compiled"
else
  grep -v WARNING "$BUILD/ci.log"; status=1
fi

echo "=== the coverage scan script on report trees ==="
bash "$ROOT/test/coverage/scan-scenarios.sh" || status=1

echo "=== the continuous integration scripts ==="
bash "$ROOT/test/ci/ci-scenarios.sh" || status=1

echo "=== sandbox scenarios and example reports ==="
if groovyc -cp "$CP" -d "$BUILD/harness" "$ROOT"/test/sandbox/harness/devsecops/test/*.groovy > "$BUILD/harness.log" 2>&1; then
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/SandboxScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== coverage reports through checkCoverage, the report and the metrics ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/CoverageScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== the static analysis switch, the restored order and the staging removal ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/StaticAnalysisScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== the static analysis in parallel on Windows agents: the SAST copy, the workspace turns and the fallbacks ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/WindowsStaticScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== the SAST compile of each build tool on Linux and macOS agents ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/SastCompileScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== pipeline vars end to end ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/PipelineScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== release of the artifact that passed the gate ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/ReleaseScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== devsecops_config.yaml read, Jenkinsfile checks, platform values and extended handoff ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/ConfigScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== compatibility with the b815d55 config.yaml loader ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/CompatibilityScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== Gradle and Maven plugins from the BBH Nexus ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/PluginRepositoryScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== SQL and PL/SQL services: settings, parsers, static checks and scripts ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/DatabaseUnitScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== SQL and PL/SQL services with Liquibase ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/DatabaseScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== the real Liquibase and utPLSQL CLIs (optional) ==="
  CP="$CP" BUILD="$BUILD" bash "$ROOT/test/database/cli-scenarios.sh" || status=1
else
  grep -v WARNING "$BUILD/harness.log"; status=1
fi

echo
[ $status -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $status
