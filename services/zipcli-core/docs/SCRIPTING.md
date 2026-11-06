# ZipCLI scripting

## Quoting contract (read this first)

| Construct | Result |
|---|---|
| `'...'` | **Literal**, like POSIX. No `$` expansion; a backslash is a backslash. `'C:\new\table.txt'` is written exactly as typed; `'\n'` is two characters |
| `\'` inside `'...'` | a quote: `'it\'s'` → `it's`. The POSIX idiom `'it'\''s'` works too |
| `\\` inside `'...'` | two backslashes (the pair is consumed so the parser always agrees where the string ends). A string cannot *end* in a single backslash inside `'...'`: use `"C:\\"` |
| `"..."` | expands `$NAME`; escapes `\\` `\"` `\'` `\n` `\t` |
| `\$` in `"..."` or unquoted | a dollar, **only when a variable name follows** (so `"price\$"` keeps its backslash, which regexes need) |
| `\$` inside `'...'` | stays backslash-dollar |
| heredoc `<<'EOF'` … `EOF` | **byte-exact**, no escapes, no expansion, whichever way the delimiter is quoted. Body ends with a line break; `<<-EOF` strips leading tabs. Only for `create` / `update` (`--append` works). Must be the last thing on its line |
| unquoted `\'` `\"` | a plain quote character |

Writing exact text (Windows paths, JSON, source code) is therefore either `'...'` for one line or a heredoc:

    update /home/cfg.json <<'EOF'
    {"dir": "C:\new\table", "home": "$HOME"}
    EOF

**Not supported, by design:** pipes, redirection, command substitution, arithmetic, background jobs. Each is rejected with one message naming all four. Workarounds: separate lines instead of a pipe; `update --append` or a heredoc instead of `>`; `set NAME=value` instead of substitution; `for` instead of `xargs`. A pasted shell prompt (`$ ls`, `zipcli$ ls`) is recognised and explained, not reported as an unknown command.

An unterminated quote rejects the whole paste (nothing runs) and shows the line with a caret:

    [E_USAGE] Syntax error (line 39): unterminated quote
      39 | update /home/a.txt 'it is
         |                    ^ opened here, never closed

Quoting changed from earlier builds: inside `'...'` a backslash used to be an escape (`'a\nb'` made a line break, `'\\'` one backslash). Use `"a\nb"`, a heredoc, or `--old-file`/`--new-file` where you relied on that.

## Exit statuses and error codes

| Status | Meaning |
|---|---|
| 0 | success |
| 1 | no match (`find`, `search`, `replace --fail-on-no-match`), false `test`, or another failure |
| 2 | usage error: unknown command/flag, wrong argument count, syntax error |
| 3 | path not found |
| 4 | needs confirmation (batch stopped before the prompt) |
| 5 | partial batch (reserved: some commands did not run) |

Every error line is `[CODE] sentence`, with `line N:` in front inside a batch: `E_USAGE`, `E_NOT_FOUND`, `E_IS_DIR`, `E_NEEDS_CONFIRM`, `E_INTERNAL` (a bug in ZipCLI, never your script), `E_FAILED` (anything else). `help exit-codes` prints this table.

## Confirmations in batches

`delete` asks first. `--yes` / `-y` on that command authorises it; `set -y` does so for the session. Where nobody can answer (MCP) the batch stops **before** the prompt: `stopped at line 6: 1 command needs confirmation, 5 command(s) not run`, exit status 4, and the MCP result carries `pending_confirmation {line, command, prompt, authorize_with}`. Nothing after it runs, even with `set +e`.

## Checking a script first

`check script.zcli` (or `check_only=true` over MCP) parses without running: quoting, heredocs, unknown commands, unknown flags, argument counts. Every problem is listed with its physical line number; status 2 if any. Variables are not expanded, so wildcard/variable-dependent errors only show at run time.

## Machine-readable output and paging

`--json` on `find`, `search`, `list`, `cat`, `replace` and `help`; `--count-only` on `find`/`search`; `--offset N` (with `--max` / `--lines`) resumes a capped result, and every cap footer names the flag that continues it. `info [--json]` reports version, transport, mounts, available commands, limits and shell state. `set --reset` forgets all variables and restores defaults.

Type `help scripting` in the app for the same summary.

| Syntax | Meaning |
|---|---|
| `a; b` or one command per line | run in order |
| `a && b` / `a \|\| b` | run b only if a succeeded / failed |
| `# comment`, trailing `\` | comment, line continuation |
| `set NAME=value` → `$NAME`, `${NAME}`, `${NAME:-default}` | variables; `$?` last status, `$PWD`, `$1..$9`, `$#` |
| `for f in *.txt; do ...; done` | loop (also multi-line) |
| `run script.zcli [args]` | run a file in its own sub-session; `-y` auto-answers prompts |
| `test -e/-f/-d path`, `test a = b`, `echo`, `true`, `false`, `exit n` | helpers |
| `set +e` / `set -x` | keep going after errors / trace commands |

Notes
- Scripts **stop at the first failing command** (reported with its line number). `cmd || true` or `set +e` to continue.
- Undefined variables are left as typed, so regex `$1` / `a$` keep working at the prompt. Use `'...'` to force a literal `$`. Use `${X:-}` to get an empty string for an unset X.
- Wildcards `* ? **` are expanded for `zip copy move delete cat list` and in `for` lists. `find`, `search` and `replace` take patterns instead (`find x dir -r --name '*.md'`, `search '*.md' dir -r`) and say so if given a wildcard path.
- `delete` still asks for confirmation inside scripts (use `-f`, or `run -y`). `-f` is idempotent like POSIX `rm -f`: a path that is already gone is not an error, so `delete backup.zip -f` safely clears a previous run.
- `edit` and `saf grant` are interactive and cannot run inside a script file.
- Paste a multi-line script into the input; use the ↵ button (or Shift+Enter) to add lines by hand; Enter / Send runs.

## What is not supported

ZipCLI is a scriptable file manager, not a POSIX shell. The following are rejected with an error instead of being silently passed to a command as text:

- redirection `>` `>>` `<` — use `update --content` / `--append`
- pipes `|` — run the commands one after another, or combine `find` with `replace`
- background `&`
- command substitution (dollar-paren and backticks) and arithmetic — use `set NAME=value`

A metacharacter is only rejected when it stands alone as a word, so a regex such as `a|b` still works.

## Quoting and leading dashes

- See the quoting contract at the top.
- A word that starts with a dash is treated as a flag only when it looks like one, so `echo '-- one --'` prints as typed. Writing `--` forces everything after it to be an argument.

## Exit status

Scripts stop at the first failing command, so exit codes are part of the language; `help <command>` prints them for every command. The ones that matter most: `find` and `search` return 1 when nothing matched (grep-style, so `find TODO dir || echo none` works), `test` returns 1 when the condition is false, and `delete -f` returns 0 for paths that are already gone.

Terminal log: each command and its output is one block followed by a blank line. The small icon on the `$ command` line copies that command's whole output. To copy individual lines, long-press a line, tap more lines to add or remove them, then press Copy in the bar that appears (Cancel or Back clears the selection).

Tests (`tests/run.sh`): `ScenarioTests` (82), `MultilineTests` (117), `RecommendationTests` (112: quoting, heredoc, confirmations, check, JSON, codes) and golden transcripts in `tests/transcripts/*.zcli` + `.expected` (run in a fresh root, diffed verbatim; regenerate with `--update` and review the diff).

## Find / replace for code and rich text
- A pattern may contain line breaks (type them inside quotes). `\n` in a pattern matches LF **and** CRLF files; inserted breaks use the file's own style; untouched lines stay byte-identical. This also holds for `--regex` (a typed `\n`, a literal line break, or `\n` inside `[...]`).
- `--reindent`: ignores indentation/trailing-blank differences when matching, then re-indents your replacement to the file (keeps its relative nesting, converts tabs⇄spaces and 2/4/8-space steps). The block must start at the beginning of a line (a UTF-8 BOM before line 1 is fine). A pattern that **ends with a line break** matches whole lines and leaves the next line's indentation alone. With a one-line pattern, `--reindent` indents the extra lines of a multi-line replacement like the line where the match is.
- `--regex`: `^`/`$` mean start/end of each line (no phantom match after the last line break), `$1`/`${name}` and `\n`/`\t` work in the replacement, `--dotall` lets `.` cross lines, `-w` wraps the pattern in `\b…\b`.
- `-w` whole word, `-i` ignore case, `--dry-run` shows a before/after diff, `--backup` keeps `<file>.bak`.
- `find` is case-insensitive unless the pattern has a capital letter (escapes such as `\S` don't count). Regexes that can cross lines (`\n`, `(?s)`, `--dotall`) search whole files automatically.
- Long blocks: write them in files and use `replace f --old-file old.txt --new-file new.txt` (exact, no escape processing; one final newline is ignored, so end a file with a blank line to match whole lines).
- `find` shows matches with their original indentation; multi-line patterns (or `-m`) report the start line and the following lines.
- Tab completion never turns a pattern or replacement text into a file name; only the path argument completes.
- Binary / non-UTF-8 files are never rewritten.
- Caveat: inside *double* quotes `\n`, `\t`, `\\` are escapes; inside single quotes they are literal characters. (In `--regex` mode `\n` in a pattern always means a line break.)
