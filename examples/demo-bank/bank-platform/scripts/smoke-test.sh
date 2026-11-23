#!/usr/bin/env bash
# End-to-end check through the gateway: payment -> balances -> Kafka-driven notification.
set -euo pipefail
GW="${GATEWAY_URL:-http://localhost:8080}/api/v1"

echo "Waiting for services..."
for port in 8080 8081 8082 8083 8084; do
  for _ in $(seq 1 60); do
    curl -sf "http://localhost:$port/actuator/health" >/dev/null && break
    sleep 2
  done
  curl -sf "http://localhost:$port/actuator/health" >/dev/null || { echo "Port $port not healthy"; exit 1; }
done

pay() {
  curl -sf -X POST "$GW/payments" -H 'Content-Type: application/json' \
    -d "{\"sourceAccountId\":\"$1\",\"targetAccountId\":\"$2\",\"amount\":$3,\"currency\":\"CHF\",\"reference\":\"smoke\"}"
}

echo "1) Completed payment"
pay acc-alice acc-bob 100.00 | grep -q '"status":"COMPLETED"' || { echo "FAIL: expected COMPLETED"; exit 1; }

echo "2) Fraud rejection"
pay acc-alice acc-bob 11000.00 | grep -q '"status":"REJECTED"' || { echo "FAIL: expected REJECTED"; exit 1; }

echo "3) Insufficient funds"
pay acc-bob acc-alice 9000.00 | grep -q '"status":"FAILED"' || { echo "FAIL: expected FAILED"; exit 1; }

echo "4) Notification delivered via Kafka"
for _ in $(seq 1 15); do
  curl -sf "$GW/accounts/acc-bob/notifications" | grep -q 'You received 100' && break
  sleep 1
done
curl -sf "$GW/accounts/acc-bob/notifications" | grep -q 'You received 100' || { echo "FAIL: no notification"; exit 1; }

echo "Smoke test passed."
