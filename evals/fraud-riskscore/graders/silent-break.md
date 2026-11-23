---
type: llm
---

The answer points out that renaming the record component also renames the JSON property sent over
`POST /fraud/checks`, so payments-service (which receives `FraudCheckResult` through its HTTP client) will silently
get a missing or default value unless both sides are deployed together or the old name is kept (e.g. `@JsonProperty`
or `@JsonAlias`). Answers that only list compile errors fail.
