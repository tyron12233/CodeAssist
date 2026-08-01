// IPC for Compose @Preview process isolation (docs/compose-preview-isolation.md). The IDE lowers the preview
// in-process (it has the classpath model + Kotlin symbol service), serializes it with ComposePreviewWireCodec,
// and hands the self-contained program to ComposePreviewSessionService in the ":preview" OS process, which
// interprets + renders it off-screen (VirtualDisplay + Presentation + ComposeView) against the IDE's bundled
// Compose (the material3-flip). A runaway recomposition or crash there pegs/kills only :preview; the IDE links a
// DeathRecipient and falls back to the in-process host. Phase 1b is a blocking single-frame request/response
// (bulk over the shared filesystem, control over Binder); streaming frames + input arrive in Phases 2-3.
package dev.ide.android.preview;

interface IComposePreviewSession {
    // The :preview process id, so the IDE can confirm rendering runs in a DIFFERENT process.
    int pid();

    // Render one frame of the lowered preview at [blobFile] (a ComposePreviewWireCodec blob on the shared FS).
    // [classpath] = the module compile-classpath jars/dirs the bytecode VM interprets for library composables the
    // bundled Compose lacks (empty → bundled-only). [resRoots] = the project resource roots for R.* resolution
    // (empty → no project resources). Writes the raw ARGB_8888 pixels (widthPx*heightPx*4 bytes) to [outFile].
    // Blocking. Returns "ok\t<w>\t<h>" (pixels at [outFile]) or "err\t<message>" (→ the caller falls back
    // in-process).
    String renderOnce(
        String blobFile, in String[] classpath, in String[] resRoots, String packageName,
        int minApi, int widthPx, int heightPx, float density, boolean night, String outFile);
}
