# Example: "What breaks if I rename `PaymentDto.amount` to `value`?"

Claude Code, headless, with **only** the repograph MCP tools and `Read` allowed (no grep, no shell), run in the `demo-bank` workspace.

```bash
claude -p "What breaks if I rename the field amount to value in PaymentDto? List every affected place with repo and file:line, and say what kind of breakage each is (compile error, JSON contract change, ...)." \
  --mcp-config repograph-mcp.json --strict-mcp-config \
  --allowedTools "mcp__repograph__*,Read" --disallowedTools "Bash,Grep,Glob"
```

**13 turns, 27 s.** Tool calls:

```text
impact_of_change({"field": "PaymentDto.amount"})
get_source({"symbol": "PaymentDto"})
find_usages({"symbol": "PaymentDto.amount", "include_tests": true})
overview({})
get_source({"symbol": "PaymentService"})
get_source({"symbol": "GatewayControllerTest#routesPaymentCreation"})
search_symbols({"query": "amount"})
get_source({"symbol": "PaymentRepository"})
get_source({"symbol": "GatewayController"})
get_source({"symbol": "PaymentsClient"})
find_usages({"symbol": "PaymentDto", "include_tests": true})
```

## Answer

**Short answer:** nothing fails to compile and no tests fail. What breaks is the JSON contract: the public gateway API, and the internal gateway ↔ payments-service API during deploys.

The field is declared at `bank-contracts/src/main/java/dev/demobank/contracts/PaymentDto.java:10`. It's a record component, so the rename also turns the `amount()` accessor into `value()` and changes the JSON property from `"amount"` to `"value"`.

### 1. Compile errors: none
- **No code calls `amount()` on a `PaymentDto`** in any repo, tests included. The code graph does track field reads (it finds `.id`, `.status` and `.failureReason`), and it shows none for `.amount`.
- **Both places that build a `PaymentDto` pass arguments by position**, so they keep compiling:
  - `payments-service/src/main/java/dev/demobank/payments/PaymentService.java:90`
  - `gateway-service/src/test/java/dev/demobank/gateway/GatewayControllerTest.java:54`
- **The `request.amount()` calls in `PaymentService.java:49,56,78,79,90` are on `PaymentRequest`**, which is a different type and isn't affected.

### 2. JSON contract changes (the real breakage)
`PaymentDto` is only used as a response body. Every response below will contain `"value"` instead of `"amount"`.

| Repo | Endpoint | Handler | Who's affected |
|---|---|---|---|
| gateway-service | `POST /api/v1/payments` | `GatewayController.java:51` | **External clients.** Nothing in the workspace calls it, so it looks like the public API (apps, frontends). Anything reading `amount` gets nothing. **Breaking change.** |
| gateway-service | `GET /api/v1/payments/{id}` | `GatewayController.java:57` | Same as above. **Breaking change.** |
| payments-service | `POST /payments` | `PaymentController.java:25` | Called by gateway-service through `PaymentsClient.java:15` |
| payments-service | `GET /payments/{id}` | `PaymentController.java:31` | Called by gateway-service through `PaymentsClient.java:18` |

### 3. Risk during deployment (silent data loss)
The gateway reads the payments-service response into its own copy of `PaymentDto` from the bank-contracts library (`PaymentsClient.java:15,18`). If the two services are deployed with different bank-contracts versions, the field names won't match.

Spring Boot ignores unknown JSON properties by default, so nothing errors. The gateway just gets `amount`/`value` = `null` and passes the null on to external clients from `GatewayController.java:54,59`. To avoid this, deploy both services with the new bank-contracts in one release, or keep the old name during the transition (e.g. `@JsonAlias("amount")`, or `@JsonProperty("amount")` to keep the JSON name entirely).

### 4. Tests
`GatewayControllerTest.java:61` sends `"amount":5.00`, but that's the request body (`PaymentRequest`), so it isn't affected. The test only checks `$.status` (line 64), so it still passes. No test checks the amount in a payment response, so tests won't catch the contract change.

### Not affected
- **Kafka topic `payments.completed`:** its payload is `PaymentCompletedEvent`, which has its own `amount` field. The rename doesn't touch it.
- **`PaymentRequest`, `FraudCheckRequest` and `MoneyMovementRequest`:** each has its own separate `amount` field.
- **`PaymentRepository`:** it's an in-memory map with no database columns or serialization.

This comes from static analysis of the six repos in the workspace. It can't see callers outside the workspace (mobile or web clients of `/api/v1/payments`), API docs or OpenAPI specs, or anything that reads the field by its name as a string.

**Recommendation:** treat it as a breaking API change. Either keep `"amount"` on the wire with `@JsonProperty("amount")` and rename only the Java name, or make the rename a new API version.
