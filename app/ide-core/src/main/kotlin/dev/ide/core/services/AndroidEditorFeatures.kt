package dev.ide.core.services

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.core.EngineContext
import dev.ide.model.Module
import java.nio.file.Path

/**
 * The Android editor features that work off one view of the project's resources and sources: Go to
 * (declaration variants, usages, related files), Quick Doc, resource rename and the gutter swatches. They
 * share an [AndroidProjectFiles], and the navigation service's caret classification and occurrence sweep.
 *
 * Built by the engine rather than the service registry because it needs engine-private inputs (the layout
 * metadata, a module's custom attributes, the SDK's `data/res`) that the [EngineContext] doesn't publish.
 */
internal class AndroidEditorFeatures(
    ctx: EngineContext,
    sdkMetadata: () -> AndroidSdkMetadata,
    customAttrs: (Module) -> AndroidSdkMetadata?,
    frameworkResDir: () -> Path?,
) {
    private val files = AndroidProjectFiles(ctx)
    val navigation = AndroidNavigationService(ctx, files, frameworkResDir)
    val quickDoc = AndroidQuickDocService(files, navigation, sdkMetadata, customAttrs)
    val rename = AndroidResourceRenameService(ctx, files, navigation)
    val swatches = ResourceSwatchService(files)
}
