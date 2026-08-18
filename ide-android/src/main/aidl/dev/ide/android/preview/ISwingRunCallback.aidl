// Streaming channel from SwingRunSessionService (:preview) back to the IDE. oneway throughout, so a slow IDE
// never blocks the running program. Bulk pixels travel over the shared FS and only control over Binder, the
// same split IComposePreviewCallback uses.
package dev.ide.android.preview;

oneway interface ISwingRunCallback {
    // A new frame is ready at [frameFile] (raw RGBA_8888, [widthPx] x [heightPx]); [seq] is monotonic so the IDE
    // can drop a stale frame if it draws slower than the program repaints. The IDE reads and deletes the file.
    //
    // There is no HardwareBuffer fast path here, unlike the Compose preview. That one renders into a GPU
    // surface and so has a buffer to hand over for free; the owned toolkit paints into a CPU Bitmap, and a
    // Swing UI repaints on events rather than at 60fps, so a file per frame is the cheaper trade.
    void onFrame(String frameFile, int widthPx, int heightPx, long seq);

    // The program's stdout/stderr, as raw text (chunks may be partial lines), for the Run console.
    void onOutput(String text);

    // The program finished: every window it opened has closed, `main` threw, or it called System.exit.
    // [error] is empty on a clean exit and otherwise carries the failure to print in the console.
    void onExited(int exitCode, String error);

    // The session could not start, or died in a way that is not the program's own failure (the IDE surfaces
    // this and unbinds).
    void onError(String message);
}
