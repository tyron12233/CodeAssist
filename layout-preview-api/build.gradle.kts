plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// layout-preview-api — the owned XML-layout-preview contracts. Deliberately android-free and
// dependency-free (stdlib only): the render tree (RenderNode/Renderer/RendererRegistry), the graphics
// abstraction (RCanvas/RPaint/RPath/RImage), pure MeasureSpec math, the resolved-value model, and the
// neutral resource/attribute seams. Renderers are written once against RCanvas; each platform supplies a
// backend (Compose DrawScope, android.graphics, Skiko). The owned view base (ViewHost) is the contract the
// device Bridge and the desktop shim both implement, so the custom-view renderer drives them identically.
