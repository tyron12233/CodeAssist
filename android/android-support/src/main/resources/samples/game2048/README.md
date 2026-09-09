# 2048

The 2048 sliding-tile puzzle, built with Jetpack Compose.

Swipe up, down, left, or right to slide every tile that way. Tiles with the same number merge into one worth
double, and a new tile appears after each move. Reach the **2048** tile to win, then keep going for a high
score. The game ends when the board is full with no moves left.

## What it shows

- **Clean state modeling** — the whole game is a `List<List<Int>>` grid, and every direction reuses one
  leftward "collapse" by rotating the board and rotating back.
- **Gesture input** — `detectDragGestures` reads each swipe and picks the dominant axis.
- **Animation** — each tile animates its background with `animateColorAsState`, so merges feel smooth.
- **State hoisting** — all rules live in `Game2048State`; the UI only renders the grid and scores.

## Files

- `Game2048.kt` — the grid, the slide/merge logic, tile spawning, and game-over detection, independent of the UI.
- `MainActivity.kt` — the Compose UI: the score header, the swipeable board, and the tiles.

Press **Run** to build and install the app, or open `MainActivity.kt` and use the editor **Preview** button
to render `Game2048Preview` without leaving the IDE.
