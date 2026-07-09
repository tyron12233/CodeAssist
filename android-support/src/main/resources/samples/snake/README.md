# Snake

The classic Snake game, built entirely with Jetpack Compose.

Swipe anywhere on the board to steer. The snake grows each time it eats the red food, the score climbs,
and the snake speeds up. Run into a wall or into yourself and it is game over.

## What it shows

- **Canvas drawing** — the board, grid, food, and snake are painted in a single `Canvas` with `drawRoundRect`
  and `drawLine`.
- **A game loop** — a `LaunchedEffect` ticks the simulation on a delay that shrinks as the score grows.
- **Gesture input** — `detectDragGestures` turns each swipe into a direction change.
- **State hoisting** — all rules live in `SnakeGameState`; the composables only render the state it exposes.

## Files

- `SnakeGame.kt` — the game state and rules (grid, movement, growth, collisions), independent of the UI.
- `MainActivity.kt` — the Compose UI: score header, the board, and the play/pause button.

Press **Run** to build and install the app, or open `MainActivity.kt` and use the editor **Preview** button
to render `SnakePreview` without leaving the IDE.
