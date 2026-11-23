---
type: llm
---

The answer explains that changing `currency` from a JSON string to an object is a wire-format (JSON) break for
HTTP consumers, not only a compile error, and it warns that the gateway's `/api/v1/accounts` endpoints are public
and may have consumers outside the workspace (mobile apps, partners) that cannot be found in the code. It proposes
a compatible rollout (for example a new field or versioned endpoint, or deploying contracts and consumers first).
