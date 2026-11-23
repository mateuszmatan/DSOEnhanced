#!/usr/bin/env bash
# Fills or checks the tool folder of the test suite: a JDK 21, Groovy 2.4.21 and the jars of
# Jenkins and of its script-security plugin. test/run-all.sh sources it; it also runs alone,
# for example when the tool set of an agent image is built:
#   DEVSECOPS_TOOLS=/opt/devsecops-library-tools bash test/setup-tools.sh
# A complete folder is only read, so a read-only tool set works.
# Optional addresses, each tried before the built-in one:
#   DEVSECOPS_MAVEN_REPO    a Maven repository with the content of Maven Central
#   DEVSECOPS_JENKINS_REPO  a Maven repository with the content of repo.jenkins-ci.org/releases
#   DEVSECOPS_JDK_URL       a JDK 21 .tar.gz
# DEVSECOPS_JDK_HOME is a JDK 21 installed on the agent, used instead of the one of the folder.
# Sets TOOLS, LIBS, JENKINS_VERSION, SCRIPT_SECURITY_VERSION and DEVSECOPS_JDK.

TOOLS="${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}"
JENKINS_VERSION="2.479.3"
SCRIPT_SECURITY_VERSION="1429.v0810f1b_530f5"
LIBS="$TOOLS/jenkins-$JENKINS_VERSION-libs"
DEFAULT_MAVEN_REPO="https://repo1.maven.org/maven2"
DEFAULT_JENKINS_REPO="https://repo.jenkins-ci.org/releases"
DEFAULT_JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"

_tools_address() {
  local name="$1" value="$2" default="$3"
  value="$(printf '%s' "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s#/*$##')"
  [ -z "$value" ] && return 0
  case "$value" in
    http://*|https://*|file://*) ;;
    *)
      echo "[TOOLS] [WARN] $name '$value' is not an http, https or file address - the default $default is used" >&2
      return 0
      ;;
  esac
  if [ "$value" = "${default%/}" ]; then
    echo "[TOOLS] [INFO] $name equals the default $default and can be removed" >&2
    return 0
  fi
  printf '%s' "$value"
}

_tools_fetch() {
  local target="$1" url
  shift
  [ -s "$target" ] && return 0
  local tried=()
  for url in "$@"; do
    [ -z "$url" ] && continue
    echo "[TOOLS] downloading $(basename "$target") from $url"
    rm -f "$target.part"
    if curl -fsSL --retry 2 -o "$target.part" "$url" && [ -s "$target.part" ]; then
      mv "$target.part" "$target"
      return 0
    fi
    rm -f "$target.part"
    tried+=("$url")
    if [ "$url" != "${@: -1}" ]; then
      echo "[TOOLS] [WARN] $(basename "$target") could not be downloaded from $url - trying the next address" >&2
    fi
  done
  echo "[TOOLS] $(basename "$target") could not be downloaded from: ${tried[*]}" >&2
  return 1
}

_tools_java_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

_tools_setup() {
  local maven jenkins jdkUrl
  maven="$(_tools_address DEVSECOPS_MAVEN_REPO "${DEVSECOPS_MAVEN_REPO:-}" "$DEFAULT_MAVEN_REPO")"
  jenkins="$(_tools_address DEVSECOPS_JENKINS_REPO "${DEVSECOPS_JENKINS_REPO:-}" "$DEFAULT_JENKINS_REPO")"
  jdkUrl="$(_tools_address DEVSECOPS_JDK_URL "${DEVSECOPS_JDK_URL:-}" "$DEFAULT_JDK_URL")"

  DEVSECOPS_JDK="$TOOLS/jdk-21"
  local jdkHome="${DEVSECOPS_JDK_HOME:-}"
  jdkHome="${jdkHome%/}"
  if [ -n "$jdkHome" ]; then
    if [ "$jdkHome" = "$TOOLS/jdk-21" ]; then
      echo "[TOOLS] [INFO] DEVSECOPS_JDK_HOME equals the default $TOOLS/jdk-21 and can be removed" >&2
    elif [ -x "$jdkHome/bin/java" ] && [ "$(_tools_java_major "$jdkHome")" = "21" ]; then
      DEVSECOPS_JDK="$jdkHome"
    else
      echo "[TOOLS] [WARN] DEVSECOPS_JDK_HOME '$jdkHome' is not a JDK 21 - the JDK of $TOOLS/jdk-21 is used" >&2
    fi
  fi

  local complete=1
  [ "$DEVSECOPS_JDK" = "$TOOLS/jdk-21" ] && [ ! -x "$TOOLS/jdk-21/bin/java" ] && complete=0
  [ -x "$TOOLS/groovy-2.4.21/bin/groovy" ] || complete=0
  [ -f "$LIBS/.complete" ] || complete=0

  local locked=0
  if [ "$complete" -eq 0 ]; then
    mkdir -p "$TOOLS" || { echo "[TOOLS] The tool folder $TOOLS cannot be created" >&2; return 1; }
    if command -v flock > /dev/null 2>&1 && { exec 9> "$TOOLS/.setup.lock"; } 2> /dev/null; then
      flock 9 && locked=1
    else
      echo "[TOOLS] The tool folder is filled without a lock (no flock or the folder is read-only)"
    fi
  fi

  local status=0
  if [ "$DEVSECOPS_JDK" = "$TOOLS/jdk-21" ] && [ ! -x "$TOOLS/jdk-21/bin/java" ]; then
    if _tools_fetch "$TOOLS/jdk21.tgz" "$jdkUrl" "$DEFAULT_JDK_URL"; then
      mkdir -p "$TOOLS/jdk-21" && tar xzf "$TOOLS/jdk21.tgz" -C "$TOOLS/jdk-21" --strip-components=1 && rm -f "$TOOLS/jdk21.tgz" || status=1
    else
      status=1
    fi
  fi
  if [ "$status" -eq 0 ] && [ ! -x "$TOOLS/groovy-2.4.21/bin/groovy" ]; then
    if _tools_fetch "$TOOLS/groovy.zip" "${maven:+$maven/org/codehaus/groovy/groovy-binary/2.4.21/groovy-binary-2.4.21.zip}" \
         "https://archive.apache.org/dist/groovy/2.4.21/distribution/apache-groovy-binary-2.4.21.zip"; then
      (cd "$TOOLS" && unzip -q -o groovy.zip && rm -f groovy.zip) || status=1
    else
      status=1
    fi
  fi
  if [ "$status" -eq 0 ] && [ ! -f "$LIBS/.complete" ]; then
    mkdir -p "$LIBS"
    _tools_fetch "$TOOLS/jenkins.war" "${jenkins:+$jenkins/org/jenkins-ci/main/jenkins-war/$JENKINS_VERSION/jenkins-war-$JENKINS_VERSION.war}" \
        "https://get.jenkins.io/war-stable/$JENKINS_VERSION/jenkins.war" \
      && (cd "$LIBS" && unzip -q -o -j "$TOOLS/jenkins.war" 'WEB-INF/lib/*.jar' && rm -f groovy-all-*.jar) \
      && _tools_fetch "$TOOLS/script-security.hpi" \
        "${jenkins:+$jenkins/org/jenkins-ci/plugins/script-security/$SCRIPT_SECURITY_VERSION/script-security-$SCRIPT_SECURITY_VERSION.hpi}" \
        "https://updates.jenkins.io/download/plugins/script-security/$SCRIPT_SECURITY_VERSION/script-security.hpi" \
      && (cd "$LIBS" && unzip -q -o -j "$TOOLS/script-security.hpi" 'WEB-INF/lib/*.jar') \
      && _tools_fetch "$LIBS/caffeine-3.1.8.jar" "${maven:+$maven/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar}" \
        "$DEFAULT_MAVEN_REPO/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar" \
      && _tools_fetch "$LIBS/snakeyaml-2.2.jar" "${maven:+$maven/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar}" \
        "$DEFAULT_MAVEN_REPO/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar" \
      && _tools_fetch "$LIBS/jakarta.servlet-api-5.0.0.jar" "${maven:+$maven/jakarta/servlet/jakarta.servlet-api/5.0.0/jakarta.servlet-api-5.0.0.jar}" \
        "$DEFAULT_MAVEN_REPO/jakarta/servlet/jakarta.servlet-api/5.0.0/jakarta.servlet-api-5.0.0.jar" \
      && rm -f "$TOOLS/jenkins.war" "$TOOLS/script-security.hpi" \
      && touch "$LIBS/.complete" \
      || status=1
  fi
  if [ "$locked" -eq 1 ]; then
    flock -u 9
    exec 9>&-
  fi
  [ "$status" -eq 0 ] || return 1
  export DEVSECOPS_JDK
  echo "[TOOLS] Tool set $TOOLS: JDK $(_tools_java_major "$DEVSECOPS_JDK") at $DEVSECOPS_JDK, Groovy 2.4.21, Jenkins $JENKINS_VERSION and script-security $SCRIPT_SECURITY_VERSION jars"
}

if (return 0 2> /dev/null); then
  _tools_setup || return 1
else
  set -uo pipefail
  _tools_setup || exit 1
fi
