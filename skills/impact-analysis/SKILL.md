---
name: impact-analysis
description: Cross-repository impact analysis for Java microservice workspaces indexed by repograph. Use before changing, renaming or removing a field, DTO, record, Kafka event, REST endpoint or HTTP client that other repositories may depend on, or when asked "what breaks if...", "who calls this endpoint", "who consumes this topic" or "trace this request".
---

# Impact analysis with repograph

The repograph MCP tools answer questions about a pre-built graph of the whole workspace. They see links grep cannot: an `@HttpExchange` client in one repo calling a controller in another, or a producer and consumer joined only by a Kafka topic name held in a constant.

## Steps

1. **Orient**: call `overview` once per conversation to learn the repos, endpoints and topics.
2. **Get exact names**: call `search_symbols` with a fragment. Don't guess fully-qualified names.
3. **Ask the specific question**:
   - Field or DTO change → `impact_of_change` with `Type.field`.
   - "Who uses X" → `find_usages` (types, methods, fields, endpoints).
   - "What happens when this request arrives" → `trace_flow` from `VERB /path`, a method or a topic.
4. **Read before you claim**: call `get_source` (or Read) on every location you name. The graph is static analysis.
5. **Report**, grouped by repository, with `path:line` for every item:
   - Code that must change, split into compile breaks and silent breaks (JSON field names on the wire, Kafka payloads).
   - HTTP endpoints carrying the type. If nobody in the workspace calls an endpoint, flag it as a **possible public API**: external consumers are invisible to the graph.
   - Kafka topics carrying the type, with producers and consumers.
   - A safe rollout order. Shared contract modules go first; consumers must tolerate both shapes during the rollout.

## Limits to state when relevant

- The graph can be stale. If the user edited code since the last index, or results look wrong, suggest `/repograph:index <workspace>`.
- HTTP calls are matched by verb and path, not by configured base URL. Ambiguous matches are labelled.
- `@JsonProperty` renames, reflection and consumers outside the workspace are not visible.

If the tools fail with a connection error, Neo4j isn't running. Tell the user to run `docker compose up -d` in the repograph directory, then `/repograph:index`.
