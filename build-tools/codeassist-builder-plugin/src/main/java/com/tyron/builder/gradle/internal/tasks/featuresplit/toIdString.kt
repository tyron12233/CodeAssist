package com.tyron.builder.gradle.internal.tasks.featuresplit

import com.tyron.builder.gradle.internal.attributes.VariantAttr
import com.tyron.builder.internal.ide.dependencies.getIdString
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

fun ResolvedArtifactResult.toIdString(): String {
    return id.componentIdentifier.toIdString(
        variantProvider = { variant.attributes.getAttribute(VariantAttr.ATTRIBUTE)?.name },
        capabilitiesProvider = { variant.capabilities.joinToString(";") { it.toString() } },
    )
}

private fun encodeCapabilitiesInId(
    id: String,
    capabilitiesProvider: () -> String
): String {
    return "$id;${capabilitiesProvider.invoke()}"
}


private fun ComponentIdentifier.toIdString(
    variantProvider: () -> String?,
    capabilitiesProvider: () -> String
) : String {
    val id = when (this) {
        is ProjectComponentIdentifier -> {
            val variant = variantProvider()
            if (variant == null) {
                getIdString()
            } else {
                "${getIdString()}::${variant}"
            }
        }
        is ModuleComponentIdentifier -> "$group:$module"
        else -> toString()
    }

    return encodeCapabilitiesInId(id, capabilitiesProvider)
}