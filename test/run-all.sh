#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}"
JENKINS_VERSION="2.479.3"
SCRIPT_SECURITY_VERSION="1429.v0810f1b_530f5"
mkdir -p "$TOOLS"

fetch() {
  [ -s "$2" ] && return 0
  echo "downloading $(basename "$2")"
  curl -fsSL -o "$2" "$1"
}

if [ ! -x "$TOOLS/jdk-21/bin/java" ]; then
  fetch "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" "$TOOLS/jdk21.tgz" || exit 1
  mkdir -p "$TOOLS/jdk-21" && tar xzf "$TOOLS/jdk21.tgz" -C "$TOOLS/jdk-21" --strip-components=1 && rm -f "$TOOLS/jdk21.tgz"
fi
if [ ! -x "$TOOLS/groovy-2.4.21/bin/groovy" ]; then
  fetch "https://archive.apache.org/dist/groovy/2.4.21/distribution/apache-groovy-binary-2.4.21.zip" "$TOOLS/groovy.zip" || exit 1
  (cd "$TOOLS" && unzip -q -o groovy.zip && rm -f groovy.zip)
fi
LIBS="$TOOLS/jenkins-$JENKINS_VERSION-libs"
if [ ! -f "$LIBS/.complete" ]; then
  mkdir -p "$LIBS"
  fetch "https://get.jenkins.io/war-stable/$JENKINS_VERSION/jenkins.war" "$TOOLS/jenkins.war" || exit 1
  (cd "$LIBS" && unzip -q -o -j "$TOOLS/jenkins.war" 'WEB-INF/lib/*.jar' && rm -f groovy-all-*.jar)
  fetch "https://updates.jenkins.io/download/plugins/script-security/$SCRIPT_SECURITY_VERSION/script-security.hpi" "$TOOLS/script-security.hpi" || exit 1
  (cd "$LIBS" && unzip -q -o -j "$TOOLS/script-security.hpi" 'WEB-INF/lib/*.jar')
  fetch "https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar" "$LIBS/caffeine-3.1.8.jar" || exit 1
  fetch "https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar" "$LIBS/snakeyaml-2.2.jar" || exit 1
  fetch "https://repo1.maven.org/maven2/jakarta/servlet/jakarta.servlet-api/5.0.0/jakarta.servlet-api-5.0.0.jar" "$LIBS/jakarta.servlet-api-5.0.0.jar" || exit 1
  rm -f "$TOOLS/jenkins.war" "$TOOLS/script-security.hpi"
  touch "$LIBS/.complete"
fi

export JAVA_HOME="$TOOLS/jdk-21"
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

echo "=== compile src and vars ==="
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

if javac --release 11 -Xlint:all -Werror -d "$BUILD/portal" "$ROOT/resources/com/bbh/config/PortalConfigQuery.java" > "$BUILD/portal.log" 2>&1; then
  echo "PortalConfigQuery.java compiled for Java 11"
else
  grep -v JAVA_TOOL_OPTIONS "$BUILD/portal.log"; status=1
fi

echo "=== sandbox scenarios and example reports ==="
if groovyc -cp "$CP" -d "$BUILD/harness" "$ROOT"/test/sandbox/harness/devsecops/test/*.groovy > "$BUILD/harness.log" 2>&1; then
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/SandboxScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
  echo "=== pipeline vars end to end ==="
  groovy -cp "$CP:$BUILD/harness" "$ROOT/test/sandbox/PipelineScenarios.groovy" "$ROOT" 2>&1 | grep -v "WARNING"
  [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
else
  grep -v WARNING "$BUILD/harness.log"; status=1
fi

echo
[ $status -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $status
