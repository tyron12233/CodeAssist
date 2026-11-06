# ZipCLI in CodePrimer

ZipCLI (the Android file / search-and-replace / ZIP command line, with its scripting language) is built in as a
plugin. The AI agent gets one extra tool, `zipcli`, that runs ZipCLI commands and scripts over the **open
project**; the project root appears as `/project`. Typical uses: bulk `replace` across a source tree, `find` /
`search` with ZipCLI's smart-case and regex handling, `zip` / `unzip` of assets or release bundles, and
re-runnable `.zcli` scripts (see `services/zipcli-core/docs/SCRIPTING.md` and `examples/backup.zcli`).

## Layout

| Path | What it is | Who edits it |
|---|---|---|
| `services/zipcli-core/src/main/kotlin/com/zipcli/` | ZipCLI's platform-free sources, copied verbatim | only `sync-from-zipcli.sh` |
| `services/zipcli-core/src/main/kotlin/dev/ide/zipcli/ZipCliProject.kt` | CodePrimer glue: mounts a project directory at `/project`, runs scripts non-interactively | us |
| `services/zipcli-core/tests/`, `docs/`, `examples/`, `CHANGELOG.md` | ZipCLI's own tests and docs, copied verbatim | only `sync-from-zipcli.sh` |
| `services/zipcli-plugin/` | `ZipCliPlugin` (identity, Settings > Plugins toggle) and `ZipCliAgentTool` | us |

Not brought over: the Android app shell and UI, the SAF (folder-grant) file system and `saf` command, and the
`mcp` server commands. CodePrimer has its own agent MCP server, and projects are plain directories.

## Shared files touched (the whole upstream-conflict surface)

| File | Change |
|---|---|
| `settings.gradle.kts` | 2 `include(...)` lines + 1 line in the `"services"` group list |
| `app/ide-core/build.gradle.kts` | 1 dependency line |
| `app/ide-core/module.toml` | `{ module = "zipcli-plugin" }` in the `implementation` list |
| `app/ide-core/.../plugins/BuiltInPlugins.kt` | 1 import + 1 `BuiltInPlugin(ZipCliPlugin())` entry |
| `app/ide-core/.../agent/AgentBackend.kt` | 2 imports + the `zipCliTool` block + `+ zipCliTool` in `tools` |

Everything else is new files, so a merge can only conflict in these five. If upstream edits one of those lines,
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
