# Agentic coding

An in-IDE AI coding assistant: a chat surface backed by a tool-using agent that reads, searches,
and edits the open project. Built entirely on the existing plugin substrate (`plugin-api` /
`BuiltInPlugins`), the scoped-service container, and the `IdeBackend` port, so it adds no privileged
host wiring and can be enabled or disabled like any other built-in plugin.

The agent is CodeAssist's own agent. It is grounded as this IDE (on-device, interpreter-based runs,
no hosted Gradle, ART constraints), never as another product. Users bring their own API key for one of
several providers; nothing is bundled or proxied.

## Goals and non-goals

Goals (first increment):
- A right-edge sliding chat drawer with streamed responses, visible reasoning, and per-tool-call status.
- A provider-neutral client with native adapters for Anthropic, OpenAI, and Google Gemini.
- An agent loop that calls tools to read the project and to write files and edit configuration, gated
  by a configurable per-project permission policy.
- Bring-your-own-key configuration through the existing Settings framework.

Deferred (tracked, not built yet):
- Build and run as agent tools (the agent self-checks via per-file diagnostics instead).
- Encryption at rest for API keys (a `SecretStore` seam ships with a plaintext default).
- Sub-agents, a bundled default provider or hosted proxy, MCP, and prompt-cache tuning.

## Module layout

Two new pure-Kotlin/JVM modules, engine-agnostic so they compile and test under `CI_CORE_ONLY`
(no Android SDK), plus additive surfaces on the existing UI port and host.

```
agent-api   (dev.ide.agent)        no engine deps; coroutines only
  ^
agent-impl  (dev.ide.agent.impl)   OkHttp + okhttp-sse + kotlinx-serialization-json + platform-core
  ^
ide-core    (dev.ide.core)         implements AgentWorkspace, hosts AgentBackend + AgentPlugin
ide-ui-api  (dev.ide.ui.backend)   adds AgentService port + Ui* DTOs
ide-ui      (dev.ide.ui)           the ChatDrawer + composables
```

`agent-api` and `agent-impl` join the unconditional framework list in `settings.gradle.kts`;
`ide-ui-api` / `ide-core` / `ide-ui` remain in the Android-shell-gated block.

### `agent-api`

Provider-neutral contracts, extensible by third-party plugins:

- `LlmClient` / `LlmProvider` and the neutral request/response model (`LlmMessage`, `LlmRole`,
  `ContentPart`, `ToolSpec`, `ToolCall`, `ToolResult`, `TokenUsage`).
- `LlmStreamEvent` (sealed): `TextDelta`, `ThinkingDelta`, `ToolCallStarted`, `ToolCallArgsDelta`,
  `ToolCallCompleted`, `Usage`, `Completed(stopReason)`, `Failed(error)`. Providers emit these; the
  loop and the UI consume them.
- `AgentTool` SPI (`name`, `description`, JSON-schema `parameters`, `suspend execute(args): ToolResult`)
  and `AgentToolRegistry`.
- `AgentWorkspace`: the narrow port the agent uses to touch the project (read/search/diagnostics/write).
  `ide-core` implements it over the engine; tests use a fake.
- `AgentPermissionPolicy` and `PermissionMode` (ASK_EACH / AUTO_ACCEPT / PLAN_ONLY).
- `AgentSession` / `AgentEvent`: the observable transcript the loop produces.

### `agent-impl`

- `OkHttpLlmTransport`: the single HTTP + SSE transport (OkHttp `EventSource`), shared by all providers
  and identical on desktop and ART. A `LlmTransport` interface (streaming `sse`, plus non-streaming `get`
  for model listing and `post` for out-of-band calls like creating a context cache) keeps it swappable for
  offline tests. It retries transient pre-stream failures with backoff, honoring a provider-suggested delay.
- `AnthropicProvider`, `OpenAiProvider`, `GeminiProvider`: each maps the neutral model to and from its
  wire format and translates its streaming shape into `LlmStreamEvent`. `@Serializable` DTOs, decoded
  per SSE event with `ignoreUnknownKeys`.
- `AgentLoop`: drives request -> stream -> execute tool calls -> append results -> repeat until the model
  stops. Emits `AgentEvent`s and enforces the permission policy before any write tool runs. Each step is
  built through a `HistoryCompactor` so a long task does not re-bill the whole transcript (see below).
  Within a step, read-only tool calls run **concurrently** (a turn that reads several files pays one file's
  latency, not the sum); mutating/unknown calls run sequentially afterward so permission prompts never race
  and writes stay ordered. Result order always matches the model's call order.
- `builtinTools(workspace)`: the built-in tool set bound to an `AgentWorkspace`.
- `SystemPrompt`: assembles the CodeAssist grounding plus live project context.

## Provider abstraction

`LlmClient.chat(request): Flow<LlmStreamEvent>` is the one streaming entry point. `LlmRequest`
carries the model id, system prompt, message history, tool specs, and generation controls (the loop
sets streaming and, for capable models, adaptive thinking). Each provider owns:

- Endpoint, auth header, and request body shape: Anthropic `POST /v1/messages` with `x-api-key` +
  `anthropic-version`; OpenAI `POST /v1/chat/completions` with `Authorization: Bearer` (base URL
  configurable, so OpenAI-compatible gateways work through the same adapter); Gemini
  `POST /v1beta/models/{model}:streamGenerateContent?alt=sse` with `x-goog-api-key`.
- Streaming decode: Anthropic content-block deltas (`text_delta` / `thinking_delta` /
  `input_json_delta`), OpenAI `choices[].delta` (content and `tool_calls`), Gemini candidate `parts`
  (`text` and `functionCall`). All three normalize to `LlmStreamEvent`.
- Tool round-trip: tool specs and tool results serialized to each provider's function-calling shape.

`LlmRequest` also carries an optional `thinkingBudget` (a cap on provider reasoning tokens; null leaves
the model default) — only providers that expose a reasoning budget honor it.

Default model per provider is the strongest current model; the model is user-selectable in Settings.
Adding a provider is implementing `LlmProvider`; the client resolves providers through a registry, so a
plugin can contribute its own.

### Antigravity gateway (experimental, opt-in)

`AntigravityProvider` reaches Google's Code Assist backend
(`cloudcode-pa.googleapis.com/v1internal:streamGenerateContent`) with an OAuth **Bearer** token instead of
an API key, giving access to Gemini 3 / Claude / GPT-OSS at Antigravity's rate limits. It speaks the same
Gemini `contents`/`parts` dialect as `GeminiProvider` — both now share the request builders in `GeminiWire`
and the `GeminiStreamDecoder` — but wraps the call in a `{project, model, request, userAgent, requestId}`
envelope, nests each streamed candidate under `response`, and sends the Antigravity IDE's identity headers
(`User-Agent` / `X-Goog-Api-Client` / `Client-Metadata`). Reasoning level is carried in the model id
(`…-high` / `…-low` / `…-thinking`), so no `thinkingConfig` is sent.

The credential (`ProviderConfig.apiKey`) is an OAuth **refresh token** (starts with `1//`, exchanged for
short-lived access tokens here via `postForm` to `oauth2.googleapis.com/token`, cached until near expiry) or
a raw **access token**, optionally suffixed with an explicit project id: `<token>` or `<token>|<projectId>`.
With no project id, the free-tier project is discovered via `loadCodeAssist` (falling back to `onboardUser`).
`AntigravitySession` holds this token + project state per client session behind a mutex.

**Sign-in flow (`AntigravityOAuth`, mobile + desktop):** the provider card's "Sign in with Google" button
runs an OAuth authorization-code + PKCE flow that mints the refresh token. Because the reproduced client only
registers a loopback redirect (`http://localhost:36742/oauth-callback`), the flow stands up a one-shot local
HTTP listener on that fixed port (`java.net` sockets — identical on ART and the JVM), surfaces the consent URL
through `AgentService.antigravitySignIn` for the UI to open in the platform browser (a Custom Tab on Android
via `LocalUriHandler`; the default browser on desktop), waits for the browser to redirect back to the
listener, validates the CSRF `state`, exchanges the code, and stores the refresh token as the antigravity key.
Cancellation tears the listener down; a five-minute timeout guards an abandoned consent screen. Pasting a
token directly into the card still works as a fallback.

**This is off by default and warned in the provider sheet: it talks to an undocumented internal endpoint by
impersonating the Antigravity IDE's OAuth client, violates Google's Terms of Service, and has led to account
bans.** It is a best-effort, fragile integration (the endpoint and client identity can change without
notice), not a supported path.

## Request cost and error categorization

An agentic turn re-sends the growing history on every step, so an agent is unusually hard on token- and
request-metered tiers (a single free-tier Gemini task can exhaust the per-minute or per-day cap). Four
mechanisms keep the cost down and the failures legible:

- **History compaction** (`HistoryCompactor`, applied per step by `AgentLoop`): a stale, oversized tool
  result (older than the most recent few) has its body elided to a head excerpt plus a "call the tool
  again for the full result" marker. The recent tool results — the model's working set — and all user and
  assistant text are kept verbatim. Compaction produces a fresh view; stored history is untouched, so a
  retry re-derives the same result.
- **Bounded turn** (`AgentLoop`): `maxIterations` (tool-call rounds) and `maxTokens` (per-response output)
  are configurable via the `settings.ai.maxIterations` / `settings.ai.maxTokens` prefs (defaults 24 / 8192).
- **Gemini context caching** (`GeminiContextCache`): the stable system instruction + tool declarations are
  the largest payload re-sent each step, so they are cached provider-side via `cachedContents` and
  referenced by name (the request then omits them). The policy is conservative — nothing on a single-shot
  first turn, skipped when the payload is below the provider's minimum cacheable size, and a graceful
  fall back to inline (remembered per payload) on any error — so caching is never a net loss.
- **Gemini thinking budget** (`thinkingConfig`): the request's `thinking` flag + `thinkingBudget` map to a
  `thinkingConfig.thinkingBudget` (0 disables reasoning; 2.5 Pro, which cannot disable it, clamps up to the
  minimum), trimming reasoning-token spend against tight TPM limits.
- **Anthropic prompt caching + interleaved thinking** (`AnthropicProvider`): the system prompt, the tool
  block, and the conversation prefix each carry an `ephemeral` `cache_control` breakpoint, so the API bills
  them once and reuses them across a turn's steps (a breakpoint below the cache minimum is ignored, so it
  never hurts). When thinking is on, the `interleaved-thinking` beta header lets the model keep reasoning
  across tool calls (about tool results, not only up front).

`LlmErrors` categorizes an HTTP or in-stream error into an `LlmErrorKind` so the transport knows whether a
retry helps and the chat shows something actionable. A subtlety worth preserving: a Gemini free-tier **rate
limit** is an HTTP 429 `RESOURCE_EXHAUSTED` that reuses the same "you exceeded your current quota / billing
details" wording a truly-exhausted paid quota uses, but it is transient and carries a short `RetryInfo`
delay. The classifier therefore treats a 429 / `resource_exhausted` as a retryable `RATE_LIMIT` and reserves
the non-retryable `QUOTA` verdict for narrow true-billing signals (`insufficient_quota`, spent credit
balance) — so a per-minute limit auto-retries instead of surfacing a dead-end "billing exhausted" message.

## Tools and the engine seam

Tool implementations call the project only through `AgentWorkspace`, which `ide-core` implements over
`EngineContext` / `IdeServices`, running every call on the engine dispatcher lanes so the index,
analyzers, and synthetic classes stay consistent.

Read tools: `read_file` (overlay-preferred, optional line range), `list_dir`, `search_text`
(`findInFiles`), `find_symbol` (`searchSymbols` / `searchMembers`), `get_diagnostics` (per-file, the
merged compiler + analyzer stream, used for self-checking), `project_overview` (modules, source sets,
dependencies, facets).

Write tools (permission-gated): `create_file`, `edit_file`, `create_dir`, `rename_path`, `move_path`,
`delete_path`, `add_dependency`, `edit_module_config`.

Build/run tool (permission-gated): `run_program` compiles a module and runs its `main` on the in-process VM
via `IdeServices.runAndCapture` (headless — it does not touch the interactive run console), feeding optional
stdin then EOF and returning output + exit code + compile errors. This closes the agent's edit → run →
read-failure → fix loop; it is time-limited so a blocked or long run can't stall a turn.

Two engine additions fill the one gap (there was no public disk-persisting multi-file edit):

- `IdeServices.applyWorkspaceEdit(edit, writeDisk = true)`: generalizes the `RefactorService.rename`
  apply loop (apply `DocumentEdit`s per file in descending offset order, update the open-document
  overlay and disk together, then fire one batched `WorkspaceEventHub` mutation so invalidation and
  reindexing run once). Open editor tabs reconcile through the existing post-rename reload path.
- `IdeServices.readCurrentText(path)`: overlay-preferred read (the live buffer if open, else disk).

## Permission model

A per-project `AgentPermissionPolicy` mirrors the run sandbox's `PermissionPolicy`
(`.platform/agent-permissions.properties`). Modes:

- ASK_EACH (default): each write tool call blocks on a UI prompt (reusing the `PermissionDialog`
  pattern hosted in `AppOverlays`), with allow-once / allow-session / allow-always / deny.
- AUTO_ACCEPT: write tools run without prompting; each applied edit is surfaced in the transcript.
- PLAN_ONLY: write tools are refused with a result telling the model to propose changes as text; only
  read tools execute.

Read tools never prompt. The mode is set from the chat drawer header and persists per project.

## Settings, keys, and secrets

An "AI" `SettingsPage` (`SETTINGS_PAGE_EP`, APPLICATION scope) exposes provider selection, per-provider
API key and optional base URL, model selection, and a "Test connection" action. Keys are read and
written through a `SecretStore` seam. The default implementation persists to the existing
`prefs.properties` store (plaintext, matching the current keystore-password posture); an
encrypted-at-rest implementation (Android EncryptedSharedPreferences, desktop keychain) is a later
drop-in behind the same interface. The key field renders masked.

## Chat UI

`AgentService` (added to `IdeBackend`) exposes `chatState: StateFlow<AgentChatState>`, `send`, `stop`,
`newSession`, the permission-request flow, and provider/model configuration reads. The UI collects the
`StateFlow` and recomposes, exactly like the build console.

`ChatDrawer` is a right-edge sliding drawer (the previously-unused `RIGHT` tool-window anchor on
desktop; a from-end `PushDrawer` on mobile), toggled from the editor top bar. It is surface-agnostic
(no background of its own), mirroring `BuildConsole`. Rendering reuses `parseMarkdown` / `highlight` /
`CodeSample` for messages and code, a `StepRow`-style status row per tool call, and the `RunScreen`
`InputBar` pattern for the composer. The visual language is a futuristic, Gemini-style treatment over
the design tokens: glass message surfaces, a gradient sparkle accent, a shimmer thinking indicator, a
glowing pill composer, and a token-by-token streaming reveal. All user-facing strings are `chat_*`
keys in `strings.xml`.

## System prompt

`SystemPrompt` grounds the agent as CodeAssist and states the platform's real shape and limits: an
on-device Android/Java IDE, program runs execute by interpreting bytecode on the in-process VM (not a
forked JVM), the build system is native (no hosted Gradle), and the runtime is ART (single-threaded VM
for user code, no `invokedynamic` bootstrap, minSdk floor). It lists the available tools and the active
permission mode, then appends live project context (modules, the active file, current diagnostics),
kept after the stable prefix so the grounding stays cache-friendly.

## Extensibility

- Add a provider: implement `LlmProvider` and register it (built-ins register in `AgentPlugin`;
  third-party plugins register through their `PluginRegistration`).
- Add a tool: implement `AgentTool` and add it to the registry; tools declare their own JSON schema and
  whether they mutate (mutating tools are permission-gated automatically).

## Testing

`agent-impl` runs offline in `CI_CORE_ONLY`: provider adapters are tested against recorded SSE fixtures
through a fake `LlmTransport`, and the agent loop and tools against a fake `AgentWorkspace`. The chat UI
is verified on desktop with a headless Compose snapshot.
