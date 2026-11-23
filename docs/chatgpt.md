# Using repograph from ChatGPT

ChatGPT talks to remote MCP servers over HTTPS. repograph runs on your machine, so you put a tunnel in front of it.

1. Index your workspace and start the HTTP transport with a token:

   ```bash
   bin/repograph index ~/code/my-services
   export REPOGRAPH_HTTP_TOKEN=$(openssl rand -hex 16); echo $REPOGRAPH_HTTP_TOKEN
   bin/repograph serve --http 8765
   ```

2. Expose it. Either tool works, and both have a free tier:

   ```bash
   cloudflared tunnel --url http://localhost:8765
   # or: ngrok http 8765
   ```

3. In ChatGPT: **Settings → Apps & Connectors → Advanced settings → Developer mode**, then **Create**:
   - Name: `repograph`
   - MCP server URL: `https://<your-tunnel-host>/mcp?token=<token>`
   - Authentication: *No authentication*

4. In a new chat, enable the connector and ask, for example:
   *"Using repograph: what breaks across our services if I rename `OrderDto.total`? Group it by repo."*

## What ChatGPT sees

| Tool | Use |
|---|---|
| `overview`, `search_symbols`, `find_usages`, `impact_of_change`, `trace_flow`, `get_source` | The full repograph toolset, the same tools Claude Code uses |
| `search`, `fetch` | Document-style access in the shape ChatGPT's deep research and company-knowledge features call. `search` returns `{results: [{id, title, url}]}`, `fetch` returns the symbol's source and every cross-repo usage |

All tools are annotated read-only. URLs have the form `repograph://<repo>/<path>#L<line>`.

## Security

- The server binds to `127.0.0.1`. Only the tunnel makes it reachable.
- With `REPOGRAPH_HTTP_TOKEN` set, `/mcp` rejects requests without the token (`401`). Clients that only take a URL
  pass it as `?token=`; clients that support headers send `Authorization: Bearer <token>`.
- Stop the tunnel when you're done. Tunnel URLs aren't secret, but the token is.

## Verified

The HTTP transport is tested end to end: initialize, `tools/list` and `tools/call` over streamable HTTP, token
rejection, and Claude Code connected through `--mcp-config` with `"type": "http"`. The ChatGPT connector flow above
follows OpenAI's developer-mode setup. Check the current UI labels, because they change often.
