package dev.ide.android.support

import dev.ide.model.ContentRole
import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/**
 * The Android plugin's file-icon contributions (priority 100, above the built-in default). It only
 * answers for the targets it recognises (the `AndroidManifest.xml`, ProGuard/R8 keep-rule files, `.aidl`
 * interface definitions, the `res`/`assets`/`aidl` source roots, and Android modules) and returns null for
 * everything else so the default provider handles it. The icon ids here (`manifest`, `proguard`, `aidl`,
 * `sourceset.android-res`, `sourceset.assets`, `sourceset.aidl`, `module.android`) are matched by the UI's
 * `TreeIcons` registry.
 */
object AndroidFileIconProvider : FileIconProvider {
    override val priority: Int get() = 100

    override fun iconFor(target: IconTarget): String? = when (target) {
        is IconTarget.File -> when {
            target.fileName == "AndroidManifest.xml" -> "manifest"
            // ProGuard/R8 keep-rule files: `proguard-rules.pro`, `consumer-rules.pro`, any `*.pro`.
            target.fileName.endsWith(".pro") -> "proguard"
            target.fileName.endsWith(".aidl") -> "aidl"
            else -> null
        }
        is IconTarget.SourceRoot -> when {
            ContentRole.ANDROID_RES in target.roles -> "sourceset.android-res"
            ContentRole.ASSETS in target.roles -> "sourceset.assets"
            ContentRole.AIDL in target.roles -> "sourceset.aidl"
            else -> null
        }
        is IconTarget.ModuleNode ->
            if (target.module.facets.get(AndroidFacet.KEY) != null) "module.android" else null
        is IconTarget.PackageDir, is IconTarget.Directory -> null
    }
}
