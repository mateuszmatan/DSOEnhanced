#!/usr/bin/env bash
# Prepares the agent of a CI build for test/run-all.sh: checks the commands the suite calls,
# creates build/ci/venv with the newest Python 3.8 or later of the agent and the markdown version
# of tools/requirements-docs.txt (through PIP_INDEX_URL when it is set; that markdown version needs
# Python 3.11 or later), writes build/ci/env.sh, which puts that Python first on the PATH, and fills
# or checks the tool folder with test/setup-tools.sh. A missing command or tool fails this step,
# not the suite.
# Usage: bash ci/prepare.sh
cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
mkdir -p build/ci
missing=()
for command in bash git curl unzip tar awk sed grep sha256sum; do
  command -v "$command" > /dev/null 2>&1 || missing+=("$command")
done
python_minor() {
  "$1" --version 2>&1 | sed -n 's/^Python 3\.\([0-9][0-9]*\).*/\1/p' | head -1
}
python=""
best=7
for candidate in python3 python3.8 python3.9 python3.10 python3.11 python3.12 python3.13 python3.14 python3.15; do
  command -v "$candidate" > /dev/null 2>&1 || continue
  minor="$(python_minor "$candidate")"
  [ -n "$minor" ] && [ "$minor" -gt "$best" ] || continue
  best="$minor"
  python="$(command -v "$candidate")"
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
    needs=""
    [ "$best" -ge 11 ] || needs=", which needs Python 3.11 or later,"
    echo "[CI] [WARN] $(cat tools/requirements-docs.txt)$needs could not be installed for $("$python" --version 2>&1) (build/ci/venv.log): the user guides check is skipped"
  fi
else
  echo "[CI] [WARN] $python cannot create a virtual environment (build/ci/venv.log): it is put first on the PATH as python3, and the user guides check runs only when it has $(cat tools/requirements-docs.txt)"
  mkdir -p build/ci/bin
  ln -s "$python" build/ci/bin/python3
  bin="$ROOT/build/ci/bin"
fi
printf 'export PATH="%s:$PATH"\n' "$bin" > build/ci/env.sh
. build/ci/env.sh
bash test/setup-tools.sh
