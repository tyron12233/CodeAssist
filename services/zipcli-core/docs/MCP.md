# ZipCLI MCP server

ZipCLI can run an [MCP](https://modelcontextprotocol.io) server so an AI client can run ZipCLI commands on the phone.
Transport: **Streamable HTTP**, one endpoint: `POST http://<host>:<port>/mcp`.

## Quick start

In the app:

```
mcp start            # listens on 127.0.0.1:8765 (this phone only)
mcp token            # prints the bearer token the client must send
```

A notification ("ZipCLI MCP server running") appears while the server is up; it keeps Android from killing the
app when you switch away. Stop with `mcp stop`.

Client configuration (generic):

| Setting | Value |
|---|---|
| URL | `http://127.0.0.1:8765/mcp` |
| Header | `Authorization: Bearer <token from 'mcp token'>` |

### Reaching the phone from another machine

Prefer keeping the default (loopback only) and tunnelling:

```
adb forward tcp:8765 tcp:8765      # then use http://127.0.0.1:8765/mcp on your computer
```

`mcp start --lan` binds all interfaces so the client can use the phone's Wi-Fi address (`mcp status` lists the URLs).
That is **plain HTTP**: anyone on the same network can read the token. Use it only on a network you trust, or put a
VPN (e.g. Tailscale/WireGuard) underneath.

### Terminal command

```
mcp start [--port N] [--lan]    start (port is remembered; default 8765)
mcp stop
mcp status
mcp token [--rotate]            show the token; --rotate invalidates the old one immediately
```

## Tools

| Tool | Purpose |
|---|---|
| `run_command` | Run a command or multi-line script (`;` `&&` `\|\|`, variables, `for` loops). Args: `command` (required), `cwd`, `confirm`, `timeout_seconds` |
| `list_commands` | List commands with usage, or full flags for one (`name`) |

Behaviour worth knowing:

- **Shared shell.** The working directory and `set` variables persist between calls (like a real shell). Pass `cwd`
  to make a call independent of earlier state.
- **One command at a time.** Calls are serialized; the terminal UI is not affected, but each call is echoed there as
  `[mcp] $ <command>` so you can see what the AI is doing.
- **Non-interactive.** `edit` and `saf grant` can't open UI from a remote call and return an error (`update --content`
  is the scriptable alternative).
- **Confirmations.** A step that asks "are you sure?" (e.g. `delete`) is skipped unless the call passes
  `confirm=true`. This is a guard against accidents, **not a security boundary**: `delete -f` never asks.
- **Not reachable remotely:** `mcp` (token / start / stop) and `saf` (grants).
- **Timeouts.** Default 50 s, max 600 s per call; on timeout the command is cancelled (work already done is not
  rolled back). If you raise `timeout_seconds`, raise your client's request timeout too.
- **Output** is capped at 100,000 characters per call.
- Each result ends with `[exit <status> | cwd <dir>]`; `isError` is true when the status is non-zero.

## Protocol support

Both generations of the spec are served from the same endpoint, chosen per request:

| Client speaks | How the server recognises it | Notes |
|---|---|---|
| **2026-07-28** (stateless) | `MCP-Protocol-Version: 2026-07-28` header or `_meta` protocol version | `server/discover`, `tools/list`, `tools/call`; every result carries `resultType`, `_meta` server info and (lists) `ttlMs`/`cacheScope`. Header/body mismatches → 400 `-32020`; unsupported version → 400 `-32022` |
| **2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05** | `initialize` | `initialize`, `ping`, `tools/list`, `tools/call`. No `Mcp-Session-Id` is issued (allowed by the spec), so no `initialize` is required first |

Not implemented (all optional): SSE responses, the GET stream, sessions/DELETE (both answer **405**), JSON-RPC
batches, chunked request bodies, CORS (browser-based clients can't connect directly; use a native client or proxy).

## Security model

- Bearer token (256-bit, random) on **every** request; compared in constant time. Rotate with `mcp token --rotate`.
- Binds `127.0.0.1` unless `--lan`.
- `Origin`, when present, must be loopback (or the same host in LAN mode) → otherwise 403 (DNS-rebinding guard).
- Limits: 16 concurrent connections, 4 MB body, 30 s idle timeout.
- What the AI can touch is exactly what the app can: `/home`, `/sdcard-ext`, and any folder you granted with
  `saf grant`. Grant only what you are comfortable letting the AI modify.

## Try it with curl

```bash
adb forward tcp:8765 tcp:8765
TOKEN=...   # from 'mcp token'

# list tools (2025-era client, no handshake needed)
curl -s http://127.0.0.1:8765/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# run a command
curl -s http://127.0.0.1:8765/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_command","arguments":{"command":"list /home"}}}'

# 2026-07-28 discovery
curl -s http://127.0.0.1:8765/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: server/discover' \
  -d '{"jsonrpc":"2.0","id":3,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

## Tests

`tests/McpTests.kt` is a standalone JVM program (like the other test files): it starts the real server on a random
port and checks security, both protocol eras, keep-alive and the tools against a temp directory. It touches no
Android classes (`McpController` / `McpService` are not involved).

## Files

| File | Role |
|---|---|
| `mcp/McpProtocol.kt` | JSON-RPC / MCP logic, both protocol generations (pure Kotlin) |
| `mcp/McpHttpServer.kt` | HTTP/1.1 server, auth, Origin check, limits |
| `mcp/ZipCliMcpTools.kt` | `run_command` / `list_commands` on top of `ScriptRunner` |
| `mcp/McpController.kt` | start/stop, token storage, process-wide server |
| `mcp/McpService.kt` | foreground service (notification keeps the process alive) |
| `domain/commands/McpCommands.kt` | the `mcp` terminal command |


## Transport boundaries (also in the tool description)

- **No pipes, no redirection, no command substitution, no arithmetic.** Separate lines, `for`, heredocs.
- Single quotes are literal; byte-exact text goes in a heredoc (`update f <<'EOF' ... EOF`).
- The working directory and variables persist across `run_command` calls (`info --json` shows them; `set --reset` clears variables).
- `saf` and `mcp` are app-only: `saf list` works (read-only), everything else answers "not available in this session", which is a policy, not a typo.
- Confirmations are never answered. A step that asks stops the batch *before* the prompt; the result has `structuredContent.pending_confirmation` and `not_run`. Re-issue with `confirm=true` or `--yes` on that command.
- Output is capped at 100000 characters; a cut is marked with the flags that page it (`--max`, `--lines`, `--offset`, `--count-only`, `--json`).
- `check_only=true` validates a script without running it; `report="json"` adds a per-command `{line, command, status, message}` array.
- `structuredContent` always has `exit` and `cwd`; `history` also records MCP commands.
- Exit statuses and `[E_*]` codes: see docs/SCRIPTING.md.
