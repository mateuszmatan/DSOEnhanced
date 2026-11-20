package com.bbh.build

import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils

class JdkToolchainSetup implements Serializable {

    private final def script
    private final PipelineState state

    JdkToolchainSetup(def script, PipelineState state) {
        this.script = script
        this.state = state
    }

    String detectTool() {
        String cfgTool = (state.cfg.buildTool ?: '').toString().trim()
        if (cfgTool && !cfgTool.equalsIgnoreCase('auto')) {
            if (BuildUtils.isValidBuildTool(cfgTool)) {
                script.echo "Detected tool from config file is ${cfgTool}"
                return BuildUtils.normalizeToolName(cfgTool)
            }
            script.echo "[WARN] Invalid buildTool '${cfgTool}' in configuration. Falling back to auto-detection."
        }
        if (script.fileExists('pubspec.yaml'))  {
            script.echo "Detected tool is Flutter"
            return 'flutter'
        }
        if (script.fileExists('pom.xml')) {
            script.echo "Detected tool is Maven"
            return 'maven'
        }
        if (script.fileExists('build.gradle') || script.fileExists('build.gradle.kts')) {
            script.echo "Detected tool is Gradle"
            return 'gradle'
        }
        if (hasDotnetProjectFiles()) {
            script.echo "Detected tool is .NET"
            return 'dotnet'
        }
        if (BuildUtils.liquibaseMarkers().any { String f -> script.fileExists(f) }) {
            script.echo "Detected tool is Liquibase"
            return 'liquibase'
        }
        if (['requirements.txt', 'pyproject.toml', 'setup.py', 'Pipfile'].any { String f -> script.fileExists(f) }) {
            script.echo "Detected tool is Python (pip)"
            return 'pip'
        }
        if (['package.json', 'angular.json'].any { String f -> script.fileExists(f) }) {
            script.echo "Detected tool is Node.js (npm)"
            return 'npm'
        }
        return 'gradle'
    }

    boolean hasDotnetProjectFiles() {
        if (['global.json', 'packages.config', 'Directory.Packages.props', 'Directory.Build.props'].any { String f -> script.fileExists(f) }) {
            return true
        }
        for (String glob : ['*.sln', '*.csproj', '*.fsproj', '*.vbproj', '**/*.sln', '**/*.csproj', '**/*.fsproj', '**/*.vbproj']) {
            try {
                def hits = script.findFiles(glob: glob)
                if (hits && hits.length > 0) return true
            } catch (Exception ignored) {}
        }
        return false
    }

    String setupBuildTool() {
        String buildTool = detectTool()
        if (buildTool in ['liquibase', 'npm', 'node', 'pip', 'ios'] || BuildUtils.isDotnetTool(buildTool)) {
            String javaPath = state.cfg?.javaPath
            if (javaPath) {
                script.env.JAVA_HOME = javaPath
                script.env.PATH = "${javaPath}/bin:${script.env.PATH}"
            }
            return buildTool
        }
        boolean hasInstalled = BuildUtils.booleanValue(script.env.HAS_BUILD_TOOL_INSTALLED, false)
        if (hasInstalled) {
            return buildTool
        }
        boolean toolAutoSetup = BuildUtils.booleanValue(state.cfg.buildToolAutoSetup, false)

        String javaPath = ''
        if (!toolAutoSetup) {
            javaPath = state.cfg?.javaPath
            if (!javaPath) {
                script.error "JAVA_HOME parameter is not specified"
            }
            if (buildTool == "flutter") {
                return buildTool
            }
            script.env.JAVA_HOME = javaPath
            script.env.PATH = "${javaPath}/bin:${script.env.PATH}"
            script.env.HAS_BUILD_TOOL_INSTALLED = 'true'
            return buildTool
        } else {
            javaPath = autoSetupJavaHome(buildTool, [:])
            script.env.JAVA_HOME = javaPath
            script.env.PATH = "${javaPath}/bin:${script.env.PATH}"
            script.env.HAS_BUILD_TOOL_INSTALLED = 'true'
            return buildTool
        }
    }

    String autoSetupJavaHome(String buildTool, Map extraEnv) {
        String text = buildTool == 'liquibase'
                ? autoSetupScript().replace(autoSetupElseAnchor(), autoSetupLiquibaseBranch() + autoSetupElseAnchor())
                : autoSetupScript()
        List env = ["BUILD_SYSTEM=${buildTool}"] + (extraEnv ?: [:]).collect { k, v -> "${k}=${v}".toString() }
        String javaPath = ''
        script.withEnv(env) {
            javaPath = script.sh(returnStdout: true, script: text).trim()
        }
        return javaPath
    }

    @NonCPS
    private static String autoSetupScript() {
        return '''#!/usr/bin/env bash
                    set -euo pipefail
                     
                    echo "[INFO] Installing $BUILD_SYSTEM..." >&2
                    
                    JAVA_VERSION=""
                    JAVA_HOME_SELECTED=""
                    
                    if [[ $BUILD_SYSTEM == 'maven' ]]; then
                         # Try maven.compiler.release
                        JAVA_VERSION="$(
                            sed -n \
                                's:.*<maven.compiler.release>[[:space:]]*\\([^<]*\\)[[:space:]]*</maven.compiler.release>.*:\\1:p' \
                                pom.xml |
                            head -n 1
                        )"
         
                        # Try java.version
                        if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -q '\\${'; then
                            JAVA_VERSION="$(
                                sed -n \
                                    's:.*<java.version>[[:space:]]*\\([^<]*\\)[[:space:]]*</java.version>.*:\\1:p' \
                                    pom.xml |
                                head -n 1
                            )"
                        fi
         
                        # Try maven.compiler.target
                        if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -q '\\${'; then
                            JAVA_VERSION="$(
                                sed -n \
                                    's:.*<maven.compiler.target>[[:space:]]*\\([^<]*\\)[[:space:]]*</maven.compiler.target>.*:\\1:p' \
                                    pom.xml |
                                head -n 1
                            )"
                        fi
         
                        # Try maven.compiler.source
                        if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -q '\\${'; then
                            JAVA_VERSION="$(
                                sed -n \
                                    's:.*<maven.compiler.source>[[:space:]]*\\([^<]*\\)[[:space:]]*</maven.compiler.source>.*:\\1:p' \
                                    pom.xml |
                                head -n 1
                            )"
                        fi
                    
                    elif [[ $BUILD_SYSTEM == 'gradle' ]]; then
                        if [ -f "build.gradle.kts" ]; then
                            GRADLE_BUILD_FILE="build.gradle.kts"
                        else
                            GRADLE_BUILD_FILE="build.gradle"
                        fi
         
                        JAVA_VERSION="$(
                            grep -Eo \
                                'JavaLanguageVersion\\.of\\([[:space:]]*[0-9]+' \
                                "$GRADLE_BUILD_FILE" 2>/dev/null |
                            grep -Eo '[0-9]+' |
                            head -n 1 ||
                            true
                        )"
         
                        if [ -z "$JAVA_VERSION" ]; then
                            JAVA_VERSION="$(
                                grep -Eo \
                                    'JavaVersion\\.VERSION_[0-9_]+' \
                                    "$GRADLE_BUILD_FILE" 2>/dev/null |
                                head -n 1 |
                                sed -E 's/.*VERSION_//; s/_/./g' ||
                                true
                            )"
                        fi
         
                        if [ -z "$JAVA_VERSION" ]; then
                            JAVA_VERSION="$(
                                sed -nE \
                                    's/.*(sourceCompatibility|targetCompatibility)[[:space:]]*=[[:space:]]*["'\\'']?([0-9]+(\\.[0-9]+)?).*/\\2/p' \
                                    "$GRADLE_BUILD_FILE" |
                                head -n 1
                            )"
                        fi
         
                        # Check gradle.properties as another source
                        if [ -z "$JAVA_VERSION" ] && [ -f "gradle.properties" ]; then
                            JAVA_VERSION="$(
                                sed -nE \
                                    's/^[[:space:]]*(javaVersion|java.version)[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\\2/p' \
                                    gradle.properties |
                                head -n 1
                            )"
                        fi
                    else
                        echo "[ERROR] No pom.xml, build.gradle, or build.gradle.kts was found." >&2
                        exit 1
                    fi  
                    JAVA_VERSION="$(echo "$JAVA_VERSION" | tr -d '[:space:]')"
         
                    case "$JAVA_VERSION" in
                        1.8)
                            JAVA_VERSION="8"
                            ;;
                        1.*)
                            JAVA_VERSION="${JAVA_VERSION#1.}"
                            JAVA_VERSION="${JAVA_VERSION%%.*}"
                            ;;
                        *)
                            JAVA_VERSION="${JAVA_VERSION%%.*}"
                            ;;
                    esac
         
                    if [ -z "$JAVA_VERSION" ]; then
                        echo "[ERROR] Could not determine the required Java version." >&2
                        exit 1
                    fi  
                        
                    echo "[INFO] Required Java version: $JAVA_VERSION" >&2            
                    echo "[INFO] Searching for installed JDK matching Java $JAVA_VERSION..." >&2
         
                    JAVA_HOME_SELECTED=""
                    while IFS= read -r JAVA_BIN; do
                     
                        CANDIDATE_JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
                        CANDIDATE_VERSION="$(
                            "$JAVA_BIN" -version 2>&1 |
                            sed -nE 's/.*version "([^"]+)".*/\\1/p' |
                            head -n 1
                        )"
                     
                        if echo "$CANDIDATE_VERSION" | grep -q '^1\\.'; then
                            CANDIDATE_MAJOR="$(
                                echo "$CANDIDATE_VERSION" |
                                cut -d. -f2
                            )"
                        else
                            CANDIDATE_MAJOR="$(
                                echo "$CANDIDATE_VERSION" |
                                cut -d. -f1
                            )"
                        fi
                     
                        echo "[INFO] Found JDK:" >&2
                        echo " JAVA_HOME=$CANDIDATE_JAVA_HOME" >&2
                        echo " Version=$CANDIDATE_VERSION" >&2
                        echo " Major=$CANDIDATE_MAJOR" >&2
                     
                        if [ "$CANDIDATE_MAJOR" = "$JAVA_VERSION" ]; then
                            JAVA_HOME_SELECTED="$CANDIDATE_JAVA_HOME"
                     
                            echo "[INFO] Matching Java $JAVA_VERSION found." >&2
                            echo "JAVA_HOME=$JAVA_HOME_SELECTED" >&2
                            break
                        fi
                     
                    done < <(
                        find \
                            /usr/lib/jvm \
                            /opt \
                            /usr/local \
                            "$HOME" \
                            -type f \
                            -path '*/bin/java' \
                            -perm -u+x \
                            2>/dev/null
                    )
                     
                    if [ -z "$JAVA_HOME_SELECTED" ]; then
                        echo "[ERROR] Java $JAVA_VERSION is required but was not found." >&2
                        exit 1
                    fi
                     
                    echo "$JAVA_HOME_SELECTED"
                '''
    }

    @NonCPS
    private static String autoSetupElseAnchor() {
        return '                    else\n                        echo "[ERROR] No pom.xml, build.gradle, or build.gradle.kts was found." >&2\n'
    }

    @NonCPS
    private static String autoSetupLiquibaseBranch() {
        return '                    elif [[ $BUILD_SYSTEM == \'liquibase\' ]]; then\n                        JAVA_VERSION="${DSO_JAVA_VERSION:-17}"\n'
    }
}
