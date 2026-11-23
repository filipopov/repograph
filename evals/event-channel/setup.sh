#!/usr/bin/env bash
# Puts the demo-bank workspace in the run's working directory, so both arms (with and without
# the plugin) analyse the same files with their ordinary tools.
set -euo pipefail
cp -R "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/demo-bank" ./demo-bank
