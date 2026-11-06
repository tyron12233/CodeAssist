# ZipCLI in CodePrimer

ZipCLI (the Android file / search-and-replace / ZIP command line, with its scripting language) is built in as a
plugin with two entry points, both over the **open project** (its root appears as `/project`):

- a **ZipCLI terminal tab** in the bottom console, beside Log / Problems / App log;
- a `zipcli` **tool for the AI agent**. Typical uses: bulk `replace` across a source tree, `find` /
`search` with ZipCLI's smart-case and regex handling, `zip` / `unzip` of assets or release bundles, and
re-runnable `.zcli` scripts (see `services/zipcli-core/docs/SCRIPTING.md` and `examples/backup.zcli`).

## Layout

| Path | What it is | Who edits it |
|---|---|---|
| `services/zipcli-core/src/main/kotlin/com/zipcli/` | ZipCLI's platform-free sources, copied verbatim | only `sync-from-zipcli.sh` |
| `services/zipcli-core/src/main/kotlin/dev/ide/zipcli/ZipCliProject.kt` | CodePrimer glue: mounts a project directory at `/project`, runs scripts non-interactively | us |
| `services/zipcli-core/tests/`, `docs/`, `examples/`, `CHANGELOG.md` | ZipCLI's own tests and docs, copied verbatim | only `sync-from-zipcli.sh` |
| `services/zipcli-plugin/` | `ZipCliPlugin` (identity, Settings > Plugins toggle) and `ZipCliAgentTool` | us |
| `services/zipcli-ui/` | `ZipCliUiPlugin` (the terminal tab), `TerminalPanel` / `TerminalSession` (common Compose) | us |

Not brought over: the Android app shell and its terminal UI (replaced by `zipcli-ui`), the SAF (folder-grant) file system and `saf` command, and the
`mcp` server commands. CodePrimer has its own agent MCP server, and projects are plain directories.

## Shared files touched (the whole upstream-conflict surface)

| File | Change |
|---|---|
| `settings.gradle.kts` | 2 `include(...)` lines + 1 line in the `"services"` group list |
| `app/ide-core/build.gradle.kts` | 2 dependency lines |
| `app/ide-core/module.toml` | `zipcli-plugin` and `zipcli-ui` in the `implementation` list |
| `app/ide-core/.../plugins/BuiltInPlugins.kt` | 2 imports + 1 `BuiltInPlugin(ZipCliPlugin(), ui = ZipCliUiPlugin)` entry |
| `app/ide-ui-api/.../backend/BackendServices.kt` | `FileService.notifyExternalChange()`, a default no-op |
| `app/ide-core/.../backend/FileBackend.kt` | its override (bumps the file-system epoch) |
| `app/ide-core/.../agent/AgentBackend.kt` | 2 imports + the `zipCliTool` block + `+ zipCliTool` in `tools` |

Everything else is new files, so a merge can only conflict in these files. If upstream edits one of those lines,
keep both sides: the ZipCLI lines are independent of whatever upstream added next to them.

## Keeping up with upstream CodeAssist

```sh
git remote add upstream https://github.com/tyron12233/CodeAssist.git   # once
git fetch upstream
git merge upstream/main        # or: git rebase upstream/main
```

## Keeping up with ZipCLI

```sh
services/zipcli-core/sync-from-zipcli.sh /path/to/zipcli/app
git diff --stat services/zipcli-core      # review what ZipCLI changed
services/zipcli-core/tests/run.sh         # engine tests (needs kotlinc)
```

The script copies the same file set every time and stops with an error if a new ZipCLI file needs an Android
type, so the JVM module cannot silently break.

## The terminal tab

Open the console at the bottom of the editor and pick **ZipCLI**. Enter runs the line; Up / Down (or the two arrow
buttons) walk the history; **Stop** cancels the running command; **Clear** empties the scrollback; the
scrollback is selectable. Output streams as it is printed, and a destructive step shows a **Yes / No** strip and
waits for you. The working directory and variables persist, and each project has its own session, which keeps
running (and keeps its scrollback) while you look at another console tab. After every command the file tree and
open tabs are refreshed. Your own commands run immediately; only the agent's go through the permission prompt.
The input is one line: for several commands put them in a `.zcli` file and `run` it (heredocs and `edit` need
that or the agent tool).

## Behaviour and limits

- **Permission-gated.** `zipcli` is a mutating tool, so every call goes through the agent's permission prompt.
  It is offered to the in-app chat only, never to the MCP server (that server runs allow-all).
- **Confirmations.** A destructive step that asks for confirmation stops the batch before it; the agent is told
  which command is waiting and repeats with `confirm=true` only if you asked for that action.
- **Not in Undo.** ZipCLI writes to disk directly, so its changes are not part of a chat turn's checkpoint, and an
  open editor tab may need a reload.
- **Symlinks.** `/project` confines `..` paths, but a symlink inside the project that points outside it is
  followed by the file layer.
- **Turning it off.** Settings > Plugins > ZipCLI removes the tool.
