#!/usr/bin/env bash
# Runs the scripts of the CI build against fakes: test/setup-tools.sh fills a tool folder from
# file:// mirrors while every http and https address is refused, ci/junit.py turns a log into
# JUnit, ci/generated-files.sh refuses changed committed files and tools/check-shared-files.sh
# compares the shared files of three source folders.
# Usage: bash test/ci/ci-scenarios.sh
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
failures=0
check() {
  if [ "$2" = "0" ]; then
    echo "PASS  $1"
  else
    failures=$((failures + 1))
    echo "FAIL  $1"
    [ -n "${3:-}" ] && printf '%s\n' "$3" | head -20 | sed 's/^/      /'
  fi
}
has() { grep -qF -- "$2" "$1"; }

mkdir -p "$T/bin"
REAL_CURL="$(command -v curl)"
cat > "$T/bin/curl" <<EOF
#!/usr/bin/env bash
for arg in "\$@"; do
  case "\$arg" in
    http://*|https://*) echo "curl: (7) refused in the CI scenarios: \$arg" >&2; exit 7 ;;
  esac
done
exec "$REAL_CURL" "\$@"
EOF
chmod +x "$T/bin/curl"

fake_java() {
  mkdir -p "$1/bin"
  printf '#!/usr/bin/env bash\necho "openjdk version \\"%s\\" 2024-10-15" >&2\n' "$2" > "$1/bin/java"
  chmod +x "$1/bin/java"
}
zipdir() {
  python3 -I - "$1" "$2" <<'PY'
import os, sys, zipfile
root, target = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(target, 'w') as archive:
    for folder, _, files in sorted(os.walk(root)):
        for name in sorted(files):
            path = os.path.join(folder, name)
            archive.write(path, os.path.relpath(path, root))
PY
}

M="$T/maven"
J="$T/jenkins"
mkdir -p "$T/src/jdk/jdk-21.0.5+11" "$T/src/groovy/groovy-2.4.21/bin" "$T/src/war/WEB-INF/lib" "$T/src/hpi/WEB-INF/lib"
fake_java "$T/src/jdk/jdk-21.0.5+11" 21.0.5
tar czf "$T/jdk.tgz" -C "$T/src/jdk" jdk-21.0.5+11
printf '#!/usr/bin/env bash\necho groovy\n' > "$T/src/groovy/groovy-2.4.21/bin/groovy"
chmod +x "$T/src/groovy/groovy-2.4.21/bin/groovy"
echo core > "$T/src/war/WEB-INF/lib/jenkins-core.jar"
echo groovy > "$T/src/war/WEB-INF/lib/groovy-all-2.4.21.jar"
echo script > "$T/src/hpi/WEB-INF/lib/script-security.jar"
mkdir -p "$M/org/codehaus/groovy/groovy-binary/2.4.21" "$M/com/github/ben-manes/caffeine/caffeine/3.1.8" "$M/org/yaml/snakeyaml/2.2" \
         "$M/jakarta/servlet/jakarta.servlet-api/5.0.0" "$J/org/jenkins-ci/main/jenkins-war/2.479.3" \
         "$J/org/jenkins-ci/plugins/script-security/1429.v0810f1b_530f5"
zipdir "$T/src/groovy" "$M/org/codehaus/groovy/groovy-binary/2.4.21/groovy-binary-2.4.21.zip"
zipdir "$T/src/war" "$J/org/jenkins-ci/main/jenkins-war/2.479.3/jenkins-war-2.479.3.war"
zipdir "$T/src/hpi" "$J/org/jenkins-ci/plugins/script-security/1429.v0810f1b_530f5/script-security-1429.v0810f1b_530f5.hpi"
echo caffeine > "$M/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar"
echo snakeyaml > "$M/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"
echo servlet > "$M/jakarta/servlet/jakarta.servlet-api/5.0.0/jakarta.servlet-api-5.0.0.jar"

setup() {
  local out="$1"
  shift
  env -u DEVSECOPS_MAVEN_REPO -u DEVSECOPS_JENKINS_REPO -u DEVSECOPS_JDK_URL -u DEVSECOPS_JDK_HOME \
      PATH="$T/bin:$PATH" "$@" bash "$ROOT/test/setup-tools.sh" > "$out" 2>&1
}

TOOLS="$T/tools-mirror"
setup "$T/mirror.log" DEVSECOPS_TOOLS="$TOOLS" DEVSECOPS_MAVEN_REPO="file://$M/" DEVSECOPS_JENKINS_REPO="file://$J" DEVSECOPS_JDK_URL="file://$T/jdk.tgz"
code=$?
LIBS="$TOOLS/jenkins-2.479.3-libs"
ok=1
[ "$code" = "0" ] && [ -x "$TOOLS/jdk-21/bin/java" ] && [ -x "$TOOLS/groovy-2.4.21/bin/groovy" ] && [ -f "$LIBS/.complete" ] \
  && [ -f "$LIBS/jenkins-core.jar" ] && [ ! -f "$LIBS/groovy-all-2.4.21.jar" ] && [ -f "$LIBS/script-security.jar" ] \
  && [ -s "$LIBS/caffeine-3.1.8.jar" ] && [ -s "$LIBS/snakeyaml-2.2.jar" ] && [ -s "$LIBS/jakarta.servlet-api-5.0.0.jar" ] \
  && [ ! -e "$TOOLS/jenkins.war" ] && [ -z "$(find "$TOOLS" -name '*.part')" ] \
  && has "$T/mirror.log" "downloading groovy.zip from file://$M/org/codehaus/groovy/groovy-binary/2.4.21/groovy-binary-2.4.21.zip" \
  && has "$T/mirror.log" "[TOOLS] Tool set $TOOLS: JDK 21 at $TOOLS/jdk-21, Groovy 2.4.21, Jenkins 2.479.3 and script-security 1429.v0810f1b_530f5 jars" \
  && ! has "$T/mirror.log" "[WARN]" && ok=0
check "setup-tools: an empty tool folder is filled from the Maven, Jenkins and JDK mirrors, without groovy-all and without a part file" "$ok" "$(cat "$T/mirror.log")"

before="$(find "$TOOLS" | sort)"
setup "$T/again.log" DEVSECOPS_TOOLS="$TOOLS"
code=$?
ok=1
[ "$code" = "0" ] && [ "$(find "$TOOLS" | sort)" = "$before" ] && ! has "$T/again.log" "downloading" && has "$T/again.log" "[TOOLS] Tool set $TOOLS:" && ok=0
check "setup-tools: a complete tool folder is only read: no download and no change" "$ok" "$(cat "$T/again.log")"

fake_java "$T/jdk21-home" 21.0.2
fake_java "$T/jdk17-home" 17.0.9
setup "$T/jdkhome.log" DEVSECOPS_TOOLS="$TOOLS" DEVSECOPS_JDK_HOME="$T/jdk21-home/"
code1=$?
setup "$T/jdk17.log" DEVSECOPS_TOOLS="$TOOLS" DEVSECOPS_JDK_HOME="$T/jdk17-home"
code2=$?
setup "$T/jdksame.log" DEVSECOPS_TOOLS="$TOOLS" DEVSECOPS_JDK_HOME="$TOOLS/jdk-21"
code3=$?
ok=1
[ "$code1$code2$code3" = "000" ] && has "$T/jdkhome.log" "JDK 21 at $T/jdk21-home," \
  && has "$T/jdk17.log" "[TOOLS] [WARN] DEVSECOPS_JDK_HOME '$T/jdk17-home' is not a JDK 21 - the JDK of $TOOLS/jdk-21 is used" \
  && has "$T/jdk17.log" "JDK 21 at $TOOLS/jdk-21," \
  && has "$T/jdksame.log" "[TOOLS] [INFO] DEVSECOPS_JDK_HOME equals the default $TOOLS/jdk-21 and can be removed" && ok=0
check "setup-tools: DEVSECOPS_JDK_HOME with a JDK 21 is used, another version is named in a [WARN] with the JDK of the folder, the default itself in an [INFO]" "$ok" \
  "$(cat "$T/jdkhome.log" "$T/jdk17.log" "$T/jdksame.log")"

partial() {
  rm -rf "$1"
  mkdir -p "$1"
  cp -a "$TOOLS/jdk-21" "$TOOLS/groovy-2.4.21" "$1/"
}
partial "$T/tools-wrong"
setup "$T/wrong.log" DEVSECOPS_TOOLS="$T/tools-wrong" DEVSECOPS_MAVEN_REPO="tools.bbh.com/nexus/maven" DEVSECOPS_JENKINS_REPO="file://$J"
code=$?
ok=1
[ "$code" != "0" ] && [ ! -f "$T/tools-wrong/jenkins-2.479.3-libs/.complete" ] && [ -z "$(find "$T/tools-wrong" -name '*.part')" ] \
  && has "$T/wrong.log" "[TOOLS] [WARN] DEVSECOPS_MAVEN_REPO 'tools.bbh.com/nexus/maven' is not an http, https or file address - the default https://repo1.maven.org/maven2 is used" \
  && has "$T/wrong.log" "[TOOLS] caffeine-3.1.8.jar could not be downloaded from: https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar" && ok=0
check "setup-tools: a mirror that is not an address is named in a [WARN] and the default is used; the setup fails only when the default fails too" "$ok" "$(cat "$T/wrong.log")"

partial "$T/tools-down"
setup "$T/down.log" DEVSECOPS_TOOLS="$T/tools-down" DEVSECOPS_MAVEN_REPO="file://$T/missing" DEVSECOPS_JENKINS_REPO="file://$J"
code=$?
ok=1
[ "$code" != "0" ] \
  && has "$T/down.log" "[TOOLS] [WARN] caffeine-3.1.8.jar could not be downloaded from file://$T/missing/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar - trying the next address" \
  && has "$T/down.log" "[TOOLS] caffeine-3.1.8.jar could not be downloaded from: file://$T/missing/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar" \
  && ok=0
check "setup-tools: a mirror that does not answer is followed by the default with a [WARN], and the failure names both addresses" "$ok" "$(cat "$T/down.log")"

partial "$T/tools-same"
setup "$T/same.log" DEVSECOPS_TOOLS="$T/tools-same" DEVSECOPS_MAVEN_REPO="https://repo1.maven.org/maven2/" DEVSECOPS_JENKINS_REPO="https://repo.jenkins-ci.org/releases"
ok=1
has "$T/same.log" "[TOOLS] [INFO] DEVSECOPS_MAVEN_REPO equals the default https://repo1.maven.org/maven2 and can be removed" \
  && has "$T/same.log" "[TOOLS] [INFO] DEVSECOPS_JENKINS_REPO equals the default https://repo.jenkins-ci.org/releases and can be removed" && ok=0
check "setup-tools: a mirror equal to the built-in address is named in an [INFO] that says it can be removed" "$ok" "$(cat "$T/same.log")"

cat > "$T/run-all.log" <<'EOF'
=== static checks ===
ASCII CHECK PASSED - src and vars hold printable ASCII, tabs and newlines only
DOCS MD CHECK REGENERATED - documentation.md rebuilt from documentation.confluence
SANDBOX LINT FAILED - these constructs need an administrator approval in Jenkins:
  src/com/bbh/X.groovy:3 new File(x)
Picked up JAVA_TOOL_OPTIONS: -Dx
=== pipeline vars end to end ===
PASS  full pipeline, green: the pipeline var runs to the end under the sandbox
FAIL  full pipeline, orange: the run matches the golden output
      compare a with b & <c>
PASS  full pipeline, orange: the release gate verdict is written
=== the real Liquibase and utPLSQL CLIs (optional) ===
DATABASE CLI CHECK SKIPPED - put Liquibase into the tool folder

SOME CHECKS FAILED
EOF
python3 -I "$ROOT/ci/junit.py" "$T/run-all.log" "$T/run-all.xml" > "$T/junit.out" 2>&1
code=$?
junit="$(python3 -I - "$T/run-all.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
rows = [root.get('tests'), root.get('failures'), root.get('skipped')]
for suite in root:
    for case in suite:
        state = 'failed' if case.find('failure') is not None else ('skipped' if case.find('skipped') is not None else 'passed')
        text = case.find('failure').text if case.find('failure') is not None else ''
        rows.append('%s|%s|%s|%s' % (suite.get('name'), case.get('name'), state, (text or '').replace('\n', '/')))
print('\n'.join(rows))
PY
)"
expected="8
4
1
static checks|ASCII CHECK|passed|
static checks|DOCS MD CHECK|failed|DOCS MD CHECK REGENERATED - documentation.md rebuilt from documentation.confluence
static checks|SANDBOX LINT|failed|SANDBOX LINT FAILED - these constructs need an administrator approval in Jenkins:/src/com/bbh/X.groovy:3 new File(x)
pipeline vars end to end|full pipeline, green: the pipeline var runs to the end under the sandbox|passed|
pipeline vars end to end|full pipeline, orange: the run matches the golden output|failed|compare a with b & <c>
pipeline vars end to end|full pipeline, orange: the release gate verdict is written|passed|
the real Liquibase and utPLSQL CLIs (optional)|DATABASE CLI CHECK|skipped|
summary|test/run-all.sh|failed|the log does not end with ALL CHECKS PASSED"
[ "$code" = "0" ] && [ "$junit" = "$expected" ]
check "ci/junit.py: one suite per section, PASS and FAIL lines with their indented text, the check lines (REGENERATED fails, SKIPPED is skipped) and a last case for the end of the log" "$?" \
  "$(printf 'got:\n%s\n' "$junit")"

G="$T/repository"
mkdir -p "$G/examples/reports" "$G/src" "$G/test/sandbox/golden"
(cd "$G" && git init -q && git config user.email ci@example.invalid && git config user.name ci \
  && echo one > examples/reports/full-pipeline-pass.html && echo code > src/Main.groovy && echo build/ > .gitignore \
  && git add . && git commit -q -m initial) > /dev/null 2>&1
echo two > "$G/examples/reports/full-pipeline-pass.html"
echo diff > "$G/test/sandbox/golden/full-pipeline-orange.actual.txt"
mkdir -p "$G/build/ci" && echo log > "$G/build/ci/run-all.log"
bash "$ROOT/ci/generated-files.sh" "$G" > "$T/generated-ok.log" 2>&1
code1=$?
echo changed > "$G/src/Main.groovy"
echo new > "$G/examples/reports/new-report.html"
bash "$ROOT/ci/generated-files.sh" "$G" > "$T/generated-bad.log" 2>&1
code2=$?
ok=1
[ "$code1" = "0" ] && has "$T/generated-ok.log" "GENERATED FILES CHECK PASSED" && [ "$code2" = "1" ] \
  && has "$T/generated-bad.log" "GENERATED FILES CHECK FAILED - test/run-all.sh changed committed files" \
  && has "$T/generated-bad.log" "   M src/Main.groovy" && has "$T/generated-bad.log" "  ?? examples/reports/new-report.html" \
  && ! has "$T/generated-bad.log" "full-pipeline-pass.html" && has "$G/build/ci/changed-files.diff" "+changed" && ok=0
check "ci/generated-files.sh: changed example reports and the .actual.txt files of a failing scenario pass, any other change or new file fails and its diff is kept" "$ok" \
  "$(cat "$T/generated-ok.log" "$T/generated-bad.log")"

S="$T/shared"
shared="TODO.md docs/MANAGEMENT_OVERVIEW.md docs/USER_GUIDE_PORTAL.md docs/USER_GUIDE_PORTAL.html docs/USER_GUIDE_WITHOUT_PORTAL.md
docs/USER_GUIDE_WITHOUT_PORTAL.html tools/render-user-guides.sh tools/render_doc.py tools/requirements-docs.txt"
library="ci/Jenkinsfile ci/prepare.sh ci/suite.sh ci/generated-files.sh ci/shared-files.sh ci/junit.py test/setup-tools.sh test/ci/ci-scenarios.sh"
for source in wp main oneview; do
  mkdir -p "$S/$source/docs" "$S/$source/tools" "$S/$source/ci" "$S/$source/test/ci"
  for file in $shared; do echo "$file" > "$S/$source/$file"; done
  cp "$ROOT/tools/check-shared-files.sh" "$S/$source/tools/check-shared-files.sh"
  echo "markdown==0.0.0" > "$S/$source/tools/requirements-docs.txt"
done
for source in wp main; do
  mkdir -p "$S/$source/test/sandbox" && touch "$S/$source/test/sandbox/ConfigScenarios.groovy"
  for file in $library; do mkdir -p "$(dirname "$S/$source/$file")" && echo "$file" > "$S/$source/$file"; done
done
mkdir -p "$S/main/test/oneview" "$S/main/test/fixtures/database/oneview" "$S/oneview/backend/src/regressionTest/resources/regression"
touch "$S/main/test/oneview/query-scenarios.sh" "$S/oneview/backend/build.gradle"
for file in database-product-config.yaml database-gradle-product-config.yaml database-maven-product-config.yaml; do
  echo "$file" > "$S/main/test/fixtures/database/oneview/$file"
  echo "$file" > "$S/oneview/backend/src/regressionTest/resources/regression/$file"
done
bash "$S/main/tools/check-shared-files.sh" --junit "$T/shared-ok.xml" "$S/wp" "$S/oneview/" > "$T/shared-ok.log" 2>&1
code1=$?
echo drift >> "$S/oneview/TODO.md"
echo drift >> "$S/wp/ci/suite.sh"
echo drift >> "$S/oneview/backend/src/regressionTest/resources/regression/database-product-config.yaml"
mkdir -p "$S/other"
bash "$S/main/tools/check-shared-files.sh" --junit "$T/shared-bad.xml" "$S/wp" "$S/oneview" "$S/other" > "$T/shared-bad.log" 2>&1
code2=$?
bash "$S/oneview/tools/check-shared-files.sh" "$S/wp" > "$T/shared-oneview.log" 2>&1
code3=$?
ok=1
[ "$code1" = "0" ] && has "$T/shared-ok.log" "SHARED FILES CHECK PASSED - 31 comparisons, every shared file is the same" \
  && has "$T/shared-ok.log" "[SHARED] The pages of the user guides are not rendered again: markdown" \
  && has "$T/shared-ok.xml" 'tests="31" failures="0"' && [ "$code2" = "1" ] \
  && has "$T/shared-bad.log" "SHARED FILES CHECK FAILED - TODO.md differs from $S/oneview/TODO.md" \
  && has "$T/shared-bad.log" "SHARED FILES CHECK FAILED - ci/suite.sh differs from $S/wp/ci/suite.sh" \
  && has "$T/shared-bad.log" "SHARED FILES CHECK FAILED - test/fixtures/database/oneview/database-product-config.yaml differs from $S/oneview/backend/src/regressionTest/resources/regression/database-product-config.yaml" \
  && has "$T/shared-bad.log" "SHARED FILES CHECK FAILED - $S/other is neither a branch of DSOEnhanced nor ITSS COE OneView" \
  && has "$T/shared-bad.log" "SHARED FILES CHECK FAILED - 4 of 32 comparisons differ" && has "$T/shared-bad.xml" 'tests="32" failures="4"' \
  && [ "$code3" = "1" ] && has "$T/shared-oneview.log" "SHARED FILES CHECK FAILED - TODO.md differs from $S/wp/TODO.md" \
  && ! has "$T/shared-oneview.log" "ci/suite.sh" && ok=0
check "tools/check-shared-files.sh: the files of the three sources, the CI files of the two library branches and the database configurations of main and OneView are compared, each difference is named and written to JUnit" "$ok" \
  "$(cat "$T/shared-ok.log" "$T/shared-bad.log" "$T/shared-oneview.log")"

if [ "$failures" -eq 0 ]; then
  echo "ALL CI SCENARIOS PASSED"
  exit 0
fi
echo "$failures CI SCENARIO CHECK(S) FAILED"
exit 1
