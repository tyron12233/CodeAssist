# Calculator

An interactive command-line calculator: type an arithmetic expression and it evaluates it.

## What it does

At the `>` prompt, enter something like `2 + 3 * 4` and press Enter — it prints `14.0`, respecting operator
precedence (`*` and `/` bind tighter than `+` and `-`), parentheses, and a leading minus for negation.
Type `quit` (or press Ctrl-D / end input) to stop.

## Structure

- `Calculator.java` — a **recursive-descent parser**. Each grammar rule is one method:
  - `expr` handles `+` and `-`
  - `term` handles `*` and `/`
  - `factor` handles a number, a parenthesised sub-expression, or a negation

  Because `expr` calls `term` which calls `factor`, multiplication naturally binds tighter than addition.

- `Main.java` — the **REPL**. It reads a line from `System.in`, evaluates it, prints the result, and loops.
  A malformed expression prints an error instead of crashing the loop.

## Run it

Press **Run**, then type expressions in the console:

```
Calculator — type an expression (e.g. 2 + 3 * 4), or 'quit' to exit.
> 2 + 3 * 4
2 + 3 * 4 = 14.0
> (2 + 3) * 4
(2 + 3) * 4 = 20.0
> quit
Bye!
```

## Extend it

- Add operators (e.g. `%`, or `^` for power) by extending `term`/`factor`.
- Remember the previous result and let the user reference it as `ans`.
- Print an integer when the result has no fractional part.
