# Tic-Tac-Toe

A two-player Tic-Tac-Toe game built with Jetpack Compose and Material 3.

Take turns tapping the grid. The first player to line up three marks wins, the winning line lights up, and the
scoreboard keeps a running tally across rounds. Tap **New Round** to play again, or **Reset scores** to start
the tally over.

## What it shows

- **State hoisting** — every rule lives in `TicTacToeState`; the UI just reads and renders it.
- **Snapshot state** — the board is a `mutableStateListOf`, so writing a single cell recomposes only what changed.
- **Animation** — marks pop in with an `Animatable` scale, and winning cells fade their background with
  `animateColorAsState`.
- **Material 3 theming** — a custom dark color scheme with per-player accent colors.

## Files

- `TicTacToeGame.kt` — the board state, turn handling, and win detection, independent of the UI.
- `MainActivity.kt` — the Compose UI: scoreboard, status line, the grid, and the controls.

Press **Run** to build and install the app, or open `MainActivity.kt` and use the editor **Preview** button
to render `TicTacToePreview` without leaving the IDE.
