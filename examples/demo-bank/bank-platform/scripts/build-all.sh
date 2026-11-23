#!/usr/bin/env bash
# Builds contracts + all services (with tests). Override Maven with MVN=/path/to/mvn.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MVN="${MVN:-mvn}"

"$MVN" -B -q install -f "$ROOT/bank-contracts/pom.xml"
for svc in accounts-service fraud-service payments-service notifications-service gateway-service; do
  echo "Building $svc"
  "$MVN" -B -q package -f "$ROOT/$svc/pom.xml"
done
echo "All services built."
