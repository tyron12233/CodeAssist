# Memory Match

A memory (concentration) card game built with Jetpack Compose.

Tap two cards to turn them face-up. A matching pair stays up; a mismatch flips back down after a beat. Clear
all eight pairs in as few moves and as little time as you can. Tap **New Game** to reshuffle.

## What it shows

- **Compose animation** — each card flips with a real 3D `graphicsLayer` `rotationY` rotation, and the face
  content swaps at the halfway point so the back "?" always reads correctly.
- **Coroutine-driven timing** — a `LaunchedEffect` hides a mismatched pair after a delay, and another ticks
  the elapsed-time counter once a second.
- **State hoisting** — all rules live in `MemoryGameState`; the UI only renders the cards and stats it exposes.
- **A gradient Material 3 look** — a vibrant vertical-gradient background with rounded, colorful cards.

## Files

- `MemoryGame.kt` — the deck, flip/match logic, and win detection, independent of the UI.
- `MainActivity.kt` — the Compose UI: the stats row, the animated card grid, and the win banner.

Press **Run** to build and install the app, or open `MainActivity.kt` and use the editor **Preview** button
to render `MemoryPreview` without leaving the IDE.
