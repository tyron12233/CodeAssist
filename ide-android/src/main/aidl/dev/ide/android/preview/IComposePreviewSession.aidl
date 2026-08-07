// IPC for Compose @Preview process isolation (docs/compose-preview-isolation.md). The IDE lowers the preview
// in-process (it has the classpath model + Kotlin symbol service), serializes it with ComposePreviewWireCodec,
// and hands the self-contained program to ComposePreviewSessionService in the ":preview" OS process, which
// interprets + renders it off-screen (VirtualDisplay + Presentation + ComposeView) against the IDE's bundled
// Compose (the material3-flip). A runaway recomposition or crash there pegs/kills only :preview; the IDE links a
// DeathRecipient and falls back to the in-process host.
//
// A persistent SESSION: open() stands up a live off-screen composition that STREAMS frames back over the
// callback (each frame's pixels written to the session frame dir, so bulk travels over the shared FS and only
// control travels over Binder -- the :build/XML convention). update() pushes a re-lowered program for live edit
// (state in the remote slot table survives). Input forwarding + the hang watchdog are later phases.
package dev.ide.android.preview;

import dev.ide.android.preview.IComposePreviewCallback;

interface IComposePreviewSession {
    // The :preview process id, so the IDE can confirm rendering runs in a DIFFERENT process.
    int pid();

    // Open a persistent session rendering the lowered preview at [blobFile] (a ComposePreviewWireCodec blob on
    // the shared FS). [classpath] = the module compile-classpath jars/dirs the bytecode VM interprets for library
    // composables the bundled Compose lacks (empty -> bundled-only). [resRoots] = the project resource roots
    // (empty -> no project resources). Frames stream via [cb].onFrame, pixels written under [frameDir]. Returns a
    // sessionId (>= 0) or -1 on failure (cb.onError carries the reason).
    // [wrapContent] = a wrap-to-content preview (`@Preview` with no device/size): the composable is measured at
    // its INTRINSIC size (bounded by [widthPx]x[heightPx] as a max, mirroring the in-process card) and its size
    // reported via cb.onContentSize so the IDE crops the frame + sizes the card to the content; false = the
    // content fills the fixed [widthPx]x[heightPx] surface (a device/@Preview(widthDp/heightDp) preview).
    int open(
        String blobFile, in String[] classpath, in String[] resRoots, String packageName, int minApi,
        int widthPx, int heightPx, float density, boolean night, boolean wrapContent, String frameDir,
        IComposePreviewCallback cb);

    // Live edit: push a re-lowered program into the running session; it re-renders (remembered state survives).
    // oneway — the IDE must never block on it: the service hops to its (possibly-busy) render thread to apply the
    // program, so a synchronous call stalls the IDE for as long as that thread is saturated (seen: 1s frames).
    oneway void update(int sessionId, String blobFile);

    // Live edit, but with the ComposePreviewWireCodec blob carried INLINE over Binder instead of via a shared file
    // — saves two FS syscalls + a re-read per keystroke. The IDE uses this when the encoded program fits under its
    // inline threshold (well within the async transaction buffer) and falls back to update(blobFile) otherwise.
    oneway void updateBytes(int sessionId, in byte[] blob);

    // Re-target the off-screen surface (size / density / night). May recreate the surface. oneway (fire-and-forget).
    oneway void resize(int sessionId, int widthPx, int heightPx, float density, boolean night);

    // Forward a pointer event into the off-screen composition (input forwarding). [action] is a MotionEvent
    // action (ACTION_DOWN/MOVE/UP/CANCEL); [x]/[y] are in the off-screen canvas' pixel space (the IDE maps the
    // tap from the displayed frame). oneway so a stream of MOVE events never blocks the IDE; :preview rebuilds a
    // MotionEvent and dispatches it into the Presentation decor view, so clicks/scroll/drag reach real nodes.
    oneway void dispatchInput(int sessionId, int action, float x, float y, int pointerId, long eventTimeMs);

    // Forward a key event: [action] a KeyEvent action (ACTION_DOWN/UP), [keyCode] a KeyEvent.KEYCODE_*,
    // [metaState] the shift/ctrl/alt modifiers. :preview rebuilds a KeyEvent and dispatches it into the decor
    // view, so hardware-keyboard keys, focus/nav keys (Tab/arrows/Enter), and onKeyEvent handlers fire. Soft-
    // keyboard TEXT entry (commitText) is a separate IME bridge, not this. oneway.
    oneway void dispatchKey(int sessionId, int action, int keyCode, int metaState, long eventTimeMs);

    // Tear down the session (dismiss the Presentation, release the display + VM executor). oneway.
    oneway void close(int sessionId);
}
