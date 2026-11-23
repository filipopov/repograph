# repograph

A code graph across **multiple Java repositories**: types, fields, methods, REST endpoints, HTTP clients and Kafka topics, linked across repo boundaries and stored in Neo4j.

It answers the question every microservice team asks before a contract change: *what breaks if I change this?*

```text
$ repograph impact PaymentCompletedEvent.amount

Code (8 references)
  notifications-service  READS       ...NotificationService#onPaymentCompleted(1)
  payments-service       CONSTRUCTS  ...PaymentService#execute(1)
  ...
Kafka topics carrying the type
  payments.completed     producers: [payments-service]  consumers: [notifications-service]
```

## What it extracts

| Node | From |
|---|---|
| `Repo` | Every directory in the workspace with a `pom.xml`; `DEPENDS_ON` from Maven dependencies |
| `Type`, `Field`, `Method` | All Java sources (main and test), including record components |
| `Endpoint` | `@RestController` + `@Get/Post/...Mapping` / `@RequestMapping` (class prefix included) |
| `Topic` | `@KafkaListener(topics = ...)` and `kafkaTemplate.send(topic, ...)` |

| Relationship | Meaning |
|---|---|
| `CALLS` | Method → method, via symbol resolution across repos; `inferred: true` when resolved by receiver type + name + arity |
| `READS` | Method → field (record accessor, getter, field access, method reference) |
| `CONSTRUCTS`, `USES_TYPE` | Method → type (constructor call; parameter/return type incl. generics) |
| `EXPOSES` / `CALLS_HTTP` | Controller method → endpoint / `@HttpExchange` client method → endpoint **in another repo** |
| `PUBLISHES` / `CONSUMES` / `CARRIES` | Method → topic / topic → payload type |

Spring and Kafka are recognised by annotation and type names, so no library jars are needed. Topic names and paths are resolved through string constants (`Topics.PAYMENTS_COMPLETED`), including concatenations.

## Quick start

Requires JDK 25, Maven and Docker.

```bash
docker compose up -d                       # Neo4j, browser on http://localhost:7474 (neo4j / repograph-dev)
mvn package
bin/repograph index examples/demo-bank     # a 6-repo sample bank ships with the project
bin/repograph impact PaymentCompletedEvent.amount
bin/repograph flow "POST /api/v1/payments"
```

Other commands: `overview`, `search <text>`, `usages <symbol>`, `source <symbol>`, `serve`, `serve --http`, `change`.
Connection settings: `REPOGRAPH_NEO4J_URI`, `REPOGRAPH_NEO4J_USER`, `REPOGRAPH_NEO4J_PASSWORD`. Set `REPOGRAPH_DEBUG=1` to print unresolved calls.
`bin/repograph` finds the jar (`target/`, `$REPOGRAPH_JAR` or the matching GitHub release) and a Java 25 runtime (`$REPOGRAPH_JAVA`, `$JAVA_HOME`, `PATH`).

## Claude Code plugin

```text
/plugin marketplace add filipopov/repograph
/plugin install repograph@repograph
```

| Part | What it does |
|---|---|
| MCP server `repograph` | The graph tools below, started through `bin/repograph serve` |
| Skill `impact-analysis` | Loaded by Claude itself before changing a DTO, endpoint or event: orient, resolve names, query, read the code, report by repo with `path:line`, flag possible public APIs, propose a rollout order |
| `/repograph:index <dir>` | (Re-)index a workspace |
| `/repograph:impact <symbol> [change]` | One-shot impact report, no edits |

Without the plugin: `claude mcp add repograph -- /absolute/path/to/repograph/bin/repograph serve`.

| Tool | Answers |
|---|---|
| `overview` | Repos, dependencies, every endpoint with its callers, every topic with producers and consumers |
| `search_symbols` | Exact names for a partial one |
| `find_usages` | Everything referencing a type, field, method or endpoint, across repos (incl. HTTP callers of a controller method) |
| `impact_of_change` | Code, HTTP endpoints and Kafka topics affected by changing a field |
| `trace_flow` | A request followed downstream through calls, HTTP hops and Kafka hops |
| `get_source` | Source of a symbol with line numbers |
| `search` / `fetch` | The same graph in the document shape ChatGPT connectors use (deep research, company knowledge) |

Symbols can be given as `PaymentDto`, `PaymentDto.amount`, `PaymentService#execute`, `POST /api/v1/payments` or `payments.completed`.
See [docs/examples/rename-paymentdto-amount.md](docs/examples/rename-paymentdto-amount.md) for a full run.

## ChatGPT, Claude.ai and other remote clients

`serve --http` exposes the same tools over MCP streamable HTTP:

```bash
export REPOGRAPH_HTTP_TOKEN=$(openssl rand -hex 16)
bin/repograph serve --http 8765            # http://127.0.0.1:8765/mcp, health check on /health
cloudflared tunnel --url http://localhost:8765   # or ngrok http 8765: gives you a public https URL
```

- **ChatGPT** (Settings → Apps & Connectors → Advanced → Developer mode → Create): URL
  `https://<tunnel>/mcp?token=<token>`, authentication "No authentication" (the token in the URL does the job).
  The `search` and `fetch` tools also make it usable from deep research.
- **Claude.ai / Claude Desktop** custom connector: the same URL.
- **Any MCP client** that sends headers: `Authorization: Bearer <token>`.

The server binds to `127.0.0.1` unless you pass `--host`. All tools are read-only, but `get_source` and `fetch`
return source code: always set a token before you put a tunnel in front of it.
See [docs/chatgpt.md](docs/chatgpt.md) for a walkthrough.

## Evaluation

[`evals/`](evals/) holds four change questions on the sample bank, with a hand-labelled answer key that was built
with grep and by reading the code, independently of repograph. `claude plugin eval` runs each question with the
plugin and without it. Both arms get the same files and the same read-only tools, 3 runs each:

| Case | Score with / without | Turns with / without | Cost with / without | Time with / without |
|---|---|---|---|---|
| account-currency | 0.94 / 1.00 | 14.0 / 18.3 | $0.25 / $0.31 | 40s / 46s |
| event-channel | 1.00 / 1.00 | 14.3 / 12.0 | $0.21 / $0.23 | 33s / 32s |
| fraud-riskscore | 1.00 / 1.00 | 11.3 / 7.3 | $0.18 / $0.16 | 31s / 28s |
| payment-flow | 1.00 / 1.00 | 12.3 / 18.0 | $0.19 / $0.28 | 29s / 32s |
| **mean** | **0.99 / 1.00** | 13.0 / 13.9 | $0.21 / $0.24 | 34s / 34s |

4 cases × 3 runs per arm, Claude Code 2.1.331, 2026-11-23.

What this shows, and what it doesn't:

- **Accuracy is at parity on a workspace this small.** demo-bank is 6 repos and about 1.5k lines. Grep finds every
  file, so plain Claude gets the right answer as well. The one miss with the plugin was the LLM-graded "explain the
  public-API risk" criterion, not a missing file.
- **The graph pays off on multi-hop questions.** Tracing `POST /api/v1/payments` through HTTP and Kafka took 32% fewer
  turns and cost 32% less (12.3 vs 18.0 turns). Single-hop questions cost the same or slightly more, because
  `overview` is an extra call.
- Next: a larger, noisier workspace, where grep returns hundreds of hits and endpoint paths are built from
  constants. That's where the static links should matter.

The report for the run above is [evals/results/latest.html](evals/results/latest.html).

## Change agent: one request, linked PRs in every affected repo

```bash
repograph change ../demo-bank "Rename PaymentCompletedEvent.amount to totalAmount"
repograph change ../demo-bank --dry-run "..."     # local branches and commits only
```

1. **Index**: rebuilds the graph from the workspace, so the plan sees the current code.
2. **Plan**: the model uses the repograph tools (read-only) and returns a structured plan:
   affected repos, why (with `path:line` evidence), per-repo instructions, changed fields and risks.
3. **Order**: repos are ordered by their Maven dependencies (shared contracts first). The model doesn't choose the order.
4. **Edit and verify**: per repo, on branch `change/<name>`, the model edits files in that repo only (no shell).
   The harness then runs `mvn install`. Build failures go back to the model, up to 3 attempts. A repo
   that stays red stops the run.
5. **PRs**: each green repo is pushed and gets a PR. Every PR description lists the whole change set in merge
   order, the plan, risks and a repograph impact appendix. Service CI builds against `bank-contracts` from
   the same branch name until it is merged.

A report goes to `<workspace>/.repograph-runs/`. The model is headless Claude Code (`claude -p`) with the
user's login (subscription, or `ANTHROPIC_API_KEY`). It runs isolated from the user's own settings,
CLAUDE.md, memory and MCP servers. Settings: `REPOGRAPH_MVN`, `REPOGRAPH_CLAUDE`, `REPOGRAPH_MODEL`.

## Tests

`mvn test` runs the extraction tests on a fixture workspace (`src/test/resources/workspace`). The tool tests need a
**separate** Neo4j, because they replace its graph; they are skipped without one:

```bash
docker run -d --name repograph-test -p 7688:7687 -e NEO4J_AUTH=neo4j/repograph-test neo4j:5.26-community
REPOGRAPH_TEST_NEO4J_URI=bolt://localhost:7688 mvn test
```

## Known limits

- HTTP client calls are matched to endpoints by verb and path, not by configured base URL; multiple matches are flagged `ambiguous`.
- JSON field renames (`@JsonProperty`) and consumers outside the workspace are not visible; endpoints nobody in the workspace calls are reported as a possible public API.
- Kafka publishing is detected for `KafkaTemplate` fields only.

## Roadmap

- ~~**M2**: MCP server for Claude Code~~ done
- ~~**M3**: Agent that plans a contract change and opens coordinated, green PRs in every affected repo~~ done
- ~~**M4**: Evaluation against a hand-labelled ground truth, compared with Claude without the graph~~ done, see [Evaluation](#evaluation)
- ~~**M5**: Claude Code plugin, ChatGPT / remote MCP over HTTP~~ done
