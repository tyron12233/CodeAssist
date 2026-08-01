// Streaming channel from ComposePreviewSessionService (:preview) back to the IDE (docs/compose-preview-
// isolation.md). oneway so :preview never blocks on the IDE draining frames. Each onFrame names a file under
// the session frame dir holding that frame's raw ARGB_8888 pixels (width*height*4 bytes) -- bulk over the
// shared FS, control over Binder; the IDE reads + deletes it and draws the latest seq.
package dev.ide.android.preview;

oneway interface IComposePreviewCallback {
    // A new frame is ready at [frameFile] (raw ARGB_8888, [widthPx] x [heightPx]); [seq] is monotonic so the IDE
    // can drop stale frames if it draws slower than :preview renders.
    void onFrame(String frameFile, int widthPx, int heightPx, long seq);

    // A fatal render error for the session (the IDE surfaces an error view / falls back in-process).
    void onError(String message);
}
