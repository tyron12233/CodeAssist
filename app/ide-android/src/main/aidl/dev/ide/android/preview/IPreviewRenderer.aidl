// IPC for real-view layout-preview isolation. The UI relinks the live buffer in-process (cheap, safe), then
// hands the self-contained render request to PreviewRenderService in the ":preview" OS process, which inflates
// + draws arbitrary library/user View code off the IDE's heap. A crash/OOM there kills only :preview (the
// client links a DeathRecipient and falls back to in-process rendering). Request/response, no streaming.
package dev.ide.android.preview;

import dev.ide.android.preview.IPreviewStageCallback;

interface IPreviewRenderer {
    // The :preview process id, so the UI can confirm rendering runs in a DIFFERENT process.
    int pid();

    // Register the channel for fine render-stage updates (held by the daemon; invoked during render()).
    void registerStageCallback(IPreviewStageCallback cb);

    // Render the layout (already relinked into [resourcesAp] by the UI) with the real framework + the project's
    // libraries on [classpath], writing the raw ARGB_8888 pixels to [outFile]. Blocking. Returns "ok\t<w>\t<h>"
    // (pixels at [outFile]) or "err\t<message>" (→ the caller falls back to owned rendering). When
    // [interpretClasses] is true the non-framework classes on [classpath] (jars + class dirs) are interpreted by
    // the bytecode VM rather than dexed, so nothing downloaded/user-built is loaded into ART.
    String render(
        String layoutName, int widthPx, int heightPx, float density, boolean night,
        String resourcesAp, in String[] classpath, String packageName, String themeName, int minApi,
        boolean interpretClasses, String outFile);
}
