---
name: index
description: Index (or re-index) a multi-repository Java workspace into the repograph code graph.
argument-hint: "<workspace-dir>"
disable-model-invocation: true
allowed-tools: Bash(${CLAUDE_PLUGIN_ROOT}/bin/repograph index:*)
---

Index the workspace for repograph.

Workspace: `$ARGUMENTS`. If empty, use the current working directory, or its parent if the current directory is itself a single Maven repo (has a `pom.xml`).

Run:

```bash
"${CLAUDE_PLUGIN_ROOT}/bin/repograph" index "<workspace>"
```

Then report the node and edge counts from the output, and call the `overview` tool to show the repos, endpoints and topics found.

If it fails with a Neo4j connection error, tell the user to start Neo4j (`docker compose up -d` in `${CLAUDE_PLUGIN_ROOT}`). Default credentials are `neo4j` / `repograph-dev`, overridable with `REPOGRAPH_NEO4J_URI`, `REPOGRAPH_NEO4J_USER` and `REPOGRAPH_NEO4J_PASSWORD`.
