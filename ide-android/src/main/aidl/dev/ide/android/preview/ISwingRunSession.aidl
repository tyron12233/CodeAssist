// IPC for running a Java AWT/Swing program on device (docs/compose-preview-isolation.md is the sibling design
// for @Preview). The program is INTERPRETED by the bytecode VM with its java.awt/javax.swing references
// remapped onto the owned :awt-toolkit, which paints into a Bitmap; the pixels stream back to the IDE and
// pointer events stream in.
//
// It runs in the ":preview" OS process rather than ":build" (where console runs live) for the same reason the
// Compose preview does: a user program is arbitrary code with a UI thread, and an infinite loop inside
// `paintComponent` must peg only this process. The IDE links a DeathRecipient and ends the run if it dies.
//
// Much simpler than the Compose session next door: the owned toolkit renders through the IDE's own RCanvas,
// so there is no VirtualDisplay, no Presentation, and no ImageReader — the session paints straight onto a
// Bitmap's Canvas. It also needs no live-edit `update`, because this is a program run, not a preview.
package dev.ide.android.preview;

import dev.ide.android.preview.ISwingRunCallback;

interface ISwingRunSession {
    // The :preview process id, so the IDE can confirm the program runs in a DIFFERENT process.
    int pid();

    // Start [mainClass] from the module's runtime [classpath] (class dirs + jars, exactly as a JVM would get
    // it; nothing is dexed or loaded into ART). The program's windows are painted at [widthPx] x [heightPx];
    // frames land under [frameDir]. Returns a sessionId (>= 0), or -1 on failure with the reason on
    // cb.onError. Returns as soon as the program's `main` has been handed to the toolkit thread: a GUI program
    // outlives `main`, and cb.onExited reports the real end.
    int open(
        in String[] classpath, String mainClass, in String[] args,
        int widthPx, int heightPx, String frameDir, ISwingRunCallback cb);

    // Forward a pointer event into the program's window. [action] is a RunPointer constant (DOWN/MOVE/UP/
    // CANCEL, the neutral ones, so neither side has to speak MotionEvent); [x]/[y] are in the frame's pixel
    // space, which the IDE maps from the touch on the displayed frame. oneway, so a stream of MOVE events never
    // blocks the IDE; the session queues it onto the toolkit thread, the only thread that touches the widgets.
    oneway void dispatchPointer(int sessionId, int action, float x, float y, long eventTimeMs);

    // Forward a key event. [action] is a RunKey constant (DOWN/UP), [keyCode] an AWT VK_ code, and [keyChar]
    // the character typed as an int (RunKey.CHAR_UNDEFINED when the key produces none). It reaches whichever
    // component holds focus, which the toolkit gives to the last component pressed.
    oneway void dispatchKey(int sessionId, int action, int keyCode, int keyChar, long eventTimeMs);

    // Re-target the painted surface when the run pane resizes. The program's windows are laid out again at the
    // new size and a frame follows. oneway.
    oneway void resize(int sessionId, int widthPx, int heightPx);

    // Stop the run: dispose the program's windows, unwind the interpreter, and end the session. This is what
    // the console's Stop button reaches. oneway.
    oneway void close(int sessionId);
}
