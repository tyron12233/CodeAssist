# Changelog

## 1.1.0 — LLM-friendliness pass (DEV_RECOMMENDATIONS.md)

**Breaking: single quotes are now literal (POSIX).** `'C:\new\table.txt'` is written as typed; previously `\n`, `\t`, `\\` were escapes inside `'...'` and silently corrupted writes. Only `\'` is special. Double quotes are unchanged. Use `"a\nb"` or a heredoc where the old behaviour was relied on. Failures now exit with 2 (usage) or 3 (not found) instead of always 1, and every error line carries a code (`[E_NOT_FOUND] ...`).

P0
- `find --regex` works again: the smart-case helper is a hand-written scanner (no helper regex to fail on Android's engine), and pattern errors are reported as `Invalid pattern`.
- Internal faults are `[E_INTERNAL] ... this is a bug, not your script`, never `Syntax error`.
- New heredoc write path: `create|update f <<'EOF' ... EOF` (byte-exact; `<<-` strips tabs). POSIX `'it'\''s'` accepted. Unterminated-quote errors quote the line with a caret.
- Shell prompts (`$ ls`) and bare operators get one diagnosis naming the four missing features.
- `history` defaults to the last 20; MCP commands are recorded.
- Deleted the empty `ui/TermanScreen.kt`.

P1
- Confirmation in a non-interactive run stops the batch before the prompt (`stopped at line N: 1 command needs confirmation, M command(s) not run`, status 4, MCP `pending_confirmation`). Global `--yes`/`-y` on `delete`, `set -y`.
- `--json` (find, search, list, cat, replace, help), `--count-only`, `--offset`, per-command MCP `report`, `check` / `check_only` dry run, `info [--json]`, `help --json`.
- `saf`/`mcp` over MCP explain they are app-only; `saf list` is available read-only. Tool description states the boundaries.

P2
- `list` expands wildcards; find/search explain they do not. `replace --fail-on-no-match`. `set --reset`. Unknown flags list the real ones; not-found errors list the folder; "-m 6" style mistakes name the flag. Truncation footers name the continuing flag.

Tests: new `RecommendationTests` and golden transcripts (`tests/run.sh`).

## Unreleased — hardening pass (behaviour fixes)

Fixes are grouped by severity. No new dependencies were introduced, so module.toml is unchanged;
everything uses the Kotlin stdlib and the APIs already declared there.

### Silent wrong behaviour fixed

- Lexer: a lone `>`, `<`, `|` or `&` used as a word is now rejected with an explanation instead of
  being handed to the command as text. Previously `echo hello > out.txt` printed "hello > out.txt"
  and created nothing.
- Lexer: command substitution and arithmetic (dollar-paren forms) are now rejected with a hint to use
  `set NAME=value`. Previously the text was passed through unchanged, so `echo \$((1+1))` printed
  the expression literally.
- Metacharacters glued to other text stay literal, so regexes such as `a|b` keep working.

### Semantics

- delete: `-f` now also ignores paths that do not exist, matching POSIX `rm -f`. Backup scripts can
  call `delete old.zip -f` without an existence guard. **Behaviour change.**
- zip: new `-o` / `--overwrite` replaces an existing archive (previously a hard error, which made
  every second run of a backup script fail).
- find and search: a run with no matches now exits with status 1 (grep convention), so
  `find TODO dir || echo none` takes the fallback branch. **Behaviour change.**
- CommandParser: a token that merely looks like a flag (`-- archive contents --`, `-3`) is treated as
  a positional argument; only flag-shaped tokens are parsed as flags, so real typos still get a
  "did you mean" hint that now also mentions the `--` escape.

### Documentation

- CommandSpec: new optional `exitStatus` field, rendered by `help <command>`.
  Documented for delete, find, replace, search, test, run, zip and unzip.
- help scripting and docs/SCRIPTING.md: sections on unsupported constructs, quoting, leading dashes
  and exit status.
- create/update: the --content help now says single quotes keep text literal.
- examples/backup.zcli: uses zip -o so it can be re-run.

### Tests

- tests/ScenarioTests.kt: the errexit tests now use a plain `delete nothere` (the failing case), and
  new checks cover idempotent delete -f, rejection of redirection/pipes/arithmetic, dash-prefixed
  text, grep-style no-match status, and zip -o.

### Not in this pass (proposed next steps)

- zip -o deletes the old archive before writing the new one; an atomic temp-file + move would survive
  a failure in the middle of a zip.
- if/elif/else and while: the parser only knows `for`; the `test A && cmd1 || cmd2` idiom is the
  usual trap. Needs parser + runner nodes and tests.
- Arithmetic expansion proper (counting files and sizes) instead of only an error.
- stat / wc / tail style aggregation commands.
- `run --dry-run` file-system write plan.
- list -l: a legend row for the leading type column (deferred because tests assert that output).

### Verification status

The change set was reviewed by reading the modified sources, but this environment has no Kotlin
compiler or Gradle, so nothing was compiled or executed. Run the JVM test programs in tests/ in CI
before merging.
