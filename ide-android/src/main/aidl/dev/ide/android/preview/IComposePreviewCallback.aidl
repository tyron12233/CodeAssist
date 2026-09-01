// Streaming channel from ComposePreviewSessionService (:preview) back to the IDE (docs/compose-preview-
// isolation.md). oneway so :preview never blocks on the IDE draining frames. Each onFrame names a file under
// the session frame dir holding that frame's raw ARGB_8888 pixels (width*height*4 bytes) -- bulk over the
// shared FS, control over Binder; the IDE reads + deletes it and draws the latest seq.
package dev.ide.android.preview;

import android.hardware.HardwareBuffer;

oneway interface IComposePreviewCallback {
    // A new frame is ready at [frameFile] (raw RGBA_8888, [widthPx] x [heightPx]); [seq] is monotonic so the IDE
    // can drop stale frames if it draws slower than :preview renders. The API 26-28 fallback path (no zero-copy).
    void onFrame(String frameFile, int widthPx, int heightPx, long seq);

    // ZERO-COPY fast path (API 29+): the frame IS the shared [buffer] (a HardwareBuffer, the GPU memory the
    // off-screen surface rendered into). The IDE wraps it with Bitmap.wrapHardwareBuffer and draws it directly —
    // no pixel copy, no filesystem, GPU memory shared across the process boundary. The Binder transaction dups the
    // dmabuf fd, so the IDE gets its own reference; :preview closes its handle right after this call.
    void onFrameBuffer(in HardwareBuffer buffer, int widthPx, int heightPx, long seq);

    // A fatal render error for the session (the IDE surfaces an error view / falls back in-process).
    void onError(String message);

    // A NON-fatal render problem: a content lambda inside the preview threw, so the preview still DRAWS but part
    // of it (lazy items, a slot) is missing. Null clears it once the pass renders cleanly again. This is the
    // isolated counterpart of the in-process host's "Preview partially rendered" warning; without it the
    // service's onPartialError went nowhere and an isolated preview reported no problem at all while the same
    // code in-process warned. Deduped by message on the service side -- the interpreter re-runs every
    // recomposition and hands over a FRESH Throwable each pass.
    void onPartialError(String message);

    // The calls the preview sandbox BLOCKED this pass, as "categoryId\tmember" entries (SandboxCategory.id and
    // the owner.name the code tried). A blocked call is stubbed out, so the preview may LOOK fine -- the chip is
    // the only place it is visible, which is why the isolated path has to send these rather than keep them.
    // Sent after each composition pass; an empty array clears.
    void onSandboxFindings(in String[] findings);

    // The measured CONTENT size of a wrap-to-content preview (`@Preview` with no device/size), in surface pixels.
    // The surface itself is rendered at a fixed max size (the device viewport), the composable wrapped to its
    // intrinsic size at top-left; this reports that intrinsic size so the IDE crops the streamed frame to it and
    // sizes the preview card to the content (matching the in-process host). Fires whenever the measured size
    // changes. Not sent for a fixed-size preview (its content fills the surface — content size == surface size).
    void onContentSize(int widthPx, int heightPx);
}
