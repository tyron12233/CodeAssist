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
  and identical on desktop and ART. A `LlmTransport` interface keeps it swappable for offline tests.
- `AnthropicProvider`, `OpenAiProvider`, `GeminiProvider`: each maps the neutral model to and from its
  wire format and translates its streaming shape into `LlmStreamEvent`. `@Serializable` DTOs, decoded
  per SSE event with `ignoreUnknownKeys`.
- `AgentLoop`: drives request -> stream -> execute tool calls -> append results -> repeat until the model
  stops. Emits `AgentEvent`s and enforces the permission policy before any write tool runs.
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

Default model per provider is the strongest current model; the model is user-selectable in Settings.
Adding a provider is implementing `LlmProvider`; the client resolves providers through a registry, so a
plugin can contribute its own.

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
