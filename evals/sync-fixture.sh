#!/usr/bin/env bash
# Copies examples/demo-bank into every eval case. `claude plugin eval` only mounts fixture
# directories that physically live inside the case, so each case gets its own (gitignored) copy.
set -euo pipefail
cd "$(dirname "$0")"
for case in */case.yaml; do
  dir="$(dirname "$case")"
  rm -rf "$dir/demo-bank"
  cp -R ../examples/demo-bank "$dir/demo-bank"
done
echo "fixture synced into: $(ls -d */case.yaml | xargs -n1 dirname | tr '\n' ' ')"
