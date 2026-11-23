#!/usr/bin/env bash
# Prepares the agent of a CI build for test/run-all.sh: checks the commands the suite calls,
# creates build/ci/venv with a Python 3.8 or later and the markdown version of
# tools/requirements-docs.txt (through PIP_INDEX_URL when it is set), writes build/ci/env.sh,
# which puts that Python first on the PATH, and fills or checks the tool folder with
# test/setup-tools.sh. A missing command or tool fails this step, not the suite.
# Usage: bash ci/prepare.sh
cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
mkdir -p build/ci
missing=()
for command in bash git curl unzip tar awk sed grep sha256sum; do
  command -v "$command" > /dev/null 2>&1 || missing+=("$command")
done
python=""
for candidate in python3.13 python3.12 python3.11 python3.10 python3.9 python3.8 python3; do
  if command -v "$candidate" > /dev/null 2>&1 \
     && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' > /dev/null 2>&1; then
    python="$(command -v "$candidate")"
    break
  fi
done
[ -n "$python" ] || missing+=("python3 3.8 or later")
if [ "${#missing[@]}" -gt 0 ]; then
  echo "[CI] This agent lacks what test/run-all.sh calls: ${missing[*]}"
  exit 1
fi
echo "[CI] $("$python" --version 2>&1) at $python; $(git --version); $(curl --version | head -1 | cut -d' ' -f1-2)"

bin="$ROOT/build/ci/venv/bin"
rm -rf build/ci/venv build/ci/bin
if "$python" -m venv build/ci/venv > build/ci/venv.log 2>&1; then
  if "$bin/python" -m pip install --disable-pip-version-check -q -r tools/requirements-docs.txt >> build/ci/venv.log 2>&1; then
    echo "[CI] $("$bin/python" -c 'import markdown; print("markdown " + markdown.__version__)') installed in build/ci/venv"
  else
    echo "[CI] [WARN] $(cat tools/requirements-docs.txt) could not be installed (build/ci/venv.log): the user guides check is skipped"
  fi
else
  echo "[CI] [WARN] $python cannot create a virtual environment (build/ci/venv.log): the user guides check runs only when $python has the markdown module"
  mkdir -p build/ci/bin
  ln -s "$python" build/ci/bin/python3
  bin="$ROOT/build/ci/bin"
fi
printf 'export PATH="%s:$PATH"\n' "$bin" > build/ci/env.sh
. build/ci/env.sh
bash test/setup-tools.sh
