// Fine render-stage updates streamed from the :preview process to the UI during a render, for the status
// chip ("Dexing" / "Inflating" / "Drawing"). oneway = fire-and-forget, ordered per-binder.
package dev.ide.android.preview;

interface IPreviewStageCallback {
    oneway void onStage(String stage);
}
