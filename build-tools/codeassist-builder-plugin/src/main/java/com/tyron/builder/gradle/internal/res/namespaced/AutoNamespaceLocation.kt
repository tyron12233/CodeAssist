@file:JvmName("AutoNamespaceLocation")
package com.tyron.builder.gradle.internal.res.namespaced

import com.android.utils.FileUtils
import org.gradle.api.artifacts.component.ComponentIdentifier


fun getAutoNamespacedLibraryFileName(artifactId: ComponentIdentifier): String {
    // TODO: handle collisions!
    return "${FileUtils.sanitizeFileName(artifactId.displayName)}.apk"
}