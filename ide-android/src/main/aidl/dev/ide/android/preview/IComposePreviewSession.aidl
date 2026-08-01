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
    int open(
        String blobFile, in String[] classpath, in String[] resRoots, String packageName, int minApi,
        int widthPx, int heightPx, float density, boolean night, String frameDir, IComposePreviewCallback cb);

    // Live edit: push a re-lowered program into the running session; it re-renders (remembered state survives).
    void update(int sessionId, String blobFile);

    // Re-target the off-screen surface (size / density / night). May recreate the surface.
    void resize(int sessionId, int widthPx, int heightPx, float density, boolean night);

    // Tear down the session (dismiss the Presentation, release the display + VM executor).
    void close(int sessionId);
}
