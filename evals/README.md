# repograph evals

Does the graph help Claude, compared with Claude using only Read/Glob/Grep on the same code?

Each case is a real change question on `examples/demo-bank` (6 repos), with a hand-labelled answer key. The answer
key was built with grep and by reading the code, not from repograph's output. `claude plugin eval` runs each case
twice: once with the plugin and once without it. Both arms get the same workspace copy in their working directory
(`setup.sh`) and the same read-only tools.

| Case | Question | Ground truth (graded by regex on the final answer) |
|---|---|---|
| `fraud-riskscore` | Rename `FraudCheckResult.riskScore` | `FraudRules.java`, `FraudController.java`, payments `FraudClient`, `/fraud/checks` (+ LLM grader: names the silent JSON break) |
| `account-currency` | `AccountDto.currency` String → object | `AccountController.java`, `GatewayController.java`, gateway + payments `AccountsClient`, `/api/v1/accounts` (+ LLM grader: public API risk, rollout) |
| `event-channel` | Remove `PaymentCompletedEvent.channel` | `PaymentService.java`, `PaymentEventPublisher`, `NotificationService.java`, `payments.completed` |
| `payment-flow` | Trace `POST /api/v1/payments` | `/payments`, `/fraud/checks`, `/debit`, `/credit`, `payments.completed`, notifications handler |

`used-repograph` graders are indicators only (`arm: with-only`, not scored).

## Run

```bash
docker compose up -d && mvn package
bin/repograph index examples/demo-bank      # the graph the plugin arm queries
evals/sync-fixture.sh                       # copies the fixture into each case
REPOGRAPH_JAVA=/path/to/java25 claude plugin eval . --runs 3 -j 4 --scaffold \
  --allow-real-servers --allow-tools "mcp__plugin_repograph_repograph__*" \
  --threshold 0 --json evals/results/latest.json
python3 evals/summarize.py evals/results/latest.json
```

Runs use your own Claude login (subscription or API key).
