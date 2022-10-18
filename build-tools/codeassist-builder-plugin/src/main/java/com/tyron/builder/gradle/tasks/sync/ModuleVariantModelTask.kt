package com.tyron.builder.gradle.tasks.sync

//import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
//import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.ide.common.build.filebasedproperties.variant.ArtifactOutputProperties
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.SYNC)
abstract class ModuleVariantModelTask: AbstractVariantModelTask() {

    @get:Input
    abstract val manifestPlaceholders: MapProperty<String, String>

    protected fun addVariantContent(artifactProperties: ArtifactOutputProperties.Builder) {
        artifactProperties.putAllManifestPlaceholders(manifestPlaceholders.get())
    }
}
