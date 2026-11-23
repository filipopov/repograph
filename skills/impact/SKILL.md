---
name: impact
description: Report everything across all repos that a change to a field, DTO, endpoint or Kafka event would affect.
argument-hint: "<Type.field | Type | VERB /path | topic> [what you plan to change]"
disable-model-invocation: true
---

Run an impact analysis for: `$ARGUMENTS`

Follow the `impact-analysis` skill. Use `impact_of_change` for a `Type.field`, `find_usages` for a type, method or endpoint, and `trace_flow` for a topic or endpoint. Read the code at each location, then give the grouped report with `path:line` evidence, the possible-public-API warnings and a rollout order.

Don't edit any files.
