#!/usr/bin/env bash
# Runs test/run-all.sh for the CI build, writes build/ci/junit/run-all.xml and fails the build
# when the run changed a committed file. The suite repairs some files and passes (a page of
# the documentation or of a user guide rendered again, a recreated AppScan baseline, a golden
# file recorded with RECORD_GOLDEN): on a branch they must be committed, so the CI refuses them.
# Only the example reports, whose times change on every run, and the <name>.actual.txt files
# of a failing scenario may differ after the run.
# Usage: bash ci/suite.sh
cd "$(dirname "$0")/.." || exit 1
mkdir -p build/ci/junit
[ -f build/ci/env.sh ] && . build/ci/env.sh
env -u RECORD_GOLDEN bash test/run-all.sh 2>&1 | tee build/ci/run-all.log
status=${PIPESTATUS[0]}
python3 -I ci/junit.py build/ci/run-all.log build/ci/junit/run-all.xml || status=1
bash ci/generated-files.sh || status=1
exit $status
