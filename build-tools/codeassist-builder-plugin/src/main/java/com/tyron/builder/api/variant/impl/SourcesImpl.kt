package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.component.impl.DefaultSourcesProvider
import com.tyron.builder.api.variant.SourceDirectories
import com.tyron.builder.api.variant.Sources
import com.tyron.builder.gradle.api.AndroidSourceDirectorySet
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceDirectorySet
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.file.Directory

/**
 * Implementation of [Sources] for a particular source type like java, kotlin, etc...
 *
 * @param defaultSourceProvider function to provide initial content of the sources for a specific
 * [SourceType]. These are all the basic folders set for main. buildTypes and flavors including
 * those set through the DSL settings.
 * @param projectDirectory the project's folder as a [Directory]
 * @param variantServices the variant's [VariantServices]
 * @param variantSourceSet optional variant specific [DefaultAndroidSourceSet] if there is one, null
 * otherwise (if the application does not have product flavor, there won't be one).
 */
class SourcesImpl(
    private val defaultSourceProvider: DefaultSourcesProvider,
    private val projectDirectory: Directory,
    private val variantServices: VariantServices,
    private val variantSourceSet: DefaultAndroidSourceSet?,
): Sources {

    override val java: FlatSourceDirectoriesImpl =
        FlatSourceDirectoriesImpl(
            SourceType.JAVA.folder,
            variantServices,
            variantSourceSet?.java?.filter
        ).also { sourceDirectoriesImpl ->

            defaultSourceProvider.getJava(sourceDirectoriesImpl).run {
                sourceDirectoriesImpl.addSources(this)
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.java)
        }

    override val kotlin: FlatSourceDirectoriesImpl =
        FlatSourceDirectoriesImpl(
            SourceType.KOTLIN.folder,
            variantServices,
            null,
        ).also { sourceDirectoriesImpl ->

            defaultSourceProvider.getKotlin(sourceDirectoriesImpl).run {
                sourceDirectoriesImpl.addSources(this)
            }
            updateSourceDirectories(
                sourceDirectoriesImpl,
                variantSourceSet?.kotlin as DefaultAndroidSourceDirectorySet?)
        }

    override val res: ResSourceDirectoriesImpl =
        ResSourceDirectoriesImpl(
            SourceType.RES.folder,
            variantServices,
            variantSourceSet?.res?.filter
        ).also { sourceDirectoriesImpl ->
            defaultSourceProvider.getRes(sourceDirectoriesImpl).run {
                forEach {
                    sourceDirectoriesImpl.addSources(it)
                }
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.res)
        }

    override val resources: FlatSourceDirectoriesImpl =
        FlatSourceDirectoriesImpl(
            SourceType.JAVA_RESOURCES.name,
            variantServices,
            variantSourceSet?.resources?.filter,
        ).also { sourceDirectoriesImpl ->

            defaultSourceProvider.getResources(sourceDirectoriesImpl).run {
                sourceDirectoriesImpl.addSources(this)
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.resources)
        }

    override val assets: AssetSourceDirectoriesImpl =
        AssetSourceDirectoriesImpl(
            SourceType.ASSETS.folder,
            variantServices,
            variantSourceSet?.assets?.filter
        ).also { sourceDirectoriesImpl ->

            defaultSourceProvider.getAssets(sourceDirectoriesImpl).run {
                forEach {
                    sourceDirectoriesImpl.addSources(it)
                }
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.assets)
        }

    override val jniLibs: AssetSourceDirectoriesImpl =
        AssetSourceDirectoriesImpl(
            SourceType.JNI_LIBS.folder,
            variantServices,
            variantSourceSet?.jniLibs?.filter
        ).also { sourceDirectoriesImpl ->

            defaultSourceProvider.getJniLibs(sourceDirectoriesImpl).run {
                forEach {
                    sourceDirectoriesImpl.addSources(it)
                }
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.jniLibs)
        }

    override val shaders: AssetSourceDirectoriesImpl? =
        AssetSourceDirectoriesImpl(
            SourceType.SHADERS.folder,
            variantServices,
            variantSourceSet?.shaders?.filter
        ).let { sourceDirectoriesImpl ->
            val listOfDirectoryEntries = defaultSourceProvider.getShaders(sourceDirectoriesImpl) ?: return@let null

            listOfDirectoryEntries.run {
                forEach {
                    sourceDirectoriesImpl.addSources(it)
                }
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.shaders)
            return@let sourceDirectoriesImpl
        }

    override val mlModels: AssetSourceDirectoriesImpl =
        AssetSourceDirectoriesImpl(
            SourceType.ML_MODELS.folder,
            variantServices,
            variantSourceSet?.mlModels?.filter
        ).also { sourceDirectoriesImpl ->
            defaultSourceProvider.getMlModels(sourceDirectoriesImpl).run {
                forEach {
                    sourceDirectoriesImpl.addSources(it)
                }
            }
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.mlModels)
        }

    override val aidl: SourceDirectories.Flat? by lazy(LazyThreadSafetyMode.NONE) {
        FlatSourceDirectoriesImpl(
            SourceType.AIDL.folder,
            variantServices,
            variantSourceSet?.aidl?.filter
        ).let { sourceDirectoriesImpl ->
            val defaultAidlDirectories =
                defaultSourceProvider.getAidl(sourceDirectoriesImpl) ?: return@let null
            sourceDirectoriesImpl.addSources(defaultAidlDirectories)
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.aidl)
            return@let sourceDirectoriesImpl
        }
    }


    override val renderscript: SourceDirectories.Flat? by lazy(LazyThreadSafetyMode.NONE) {
        FlatSourceDirectoriesImpl(
            SourceType.RENDERSCRIPT.folder,
            variantServices,
            variantSourceSet?.renderscript?.filter
        ).let { sourceDirectoriesImpl ->
            val defaultRenderscriptDirectories =
                defaultSourceProvider.getRenderscript(sourceDirectoriesImpl) ?: return@let null

            sourceDirectoriesImpl.addSources(defaultRenderscriptDirectories)
            updateSourceDirectories(sourceDirectoriesImpl, variantSourceSet?.renderscript)
            return@let sourceDirectoriesImpl
        }
    }

    internal val extras: NamedDomainObjectContainer<FlatSourceDirectoriesImpl> by lazy(LazyThreadSafetyMode.NONE) {
        variantServices.domainObjectContainer(
            FlatSourceDirectoriesImpl::class.java,
            SourceProviderFactory(
                variantServices,
                projectDirectory,
            ),
        )
    }

    override fun getByName(name: String): SourceDirectories.Flat = extras.maybeCreate(name)

    class SourceProviderFactory(
        private val variantServices: VariantServices,
        private val projectDirectory: Directory,
    ): NamedDomainObjectFactory<FlatSourceDirectoriesImpl> {

        override fun create(name: String): FlatSourceDirectoriesImpl =
            FlatSourceDirectoriesImpl(
                _name = name,
                variantServices = variantServices,
                variantDslFilters = null
            )
    }

    /**
     * Update SourceDirectories with the original variant specific source set from
     * [com.tyron.builder.gradle.internal.core.VariantSources] since the variant
     * specific folders are owned by this abstraction (so users can add it if needed).
     * TODO, make the VariantSources unavailable to other components in
     * AGP as they should all use this [SourcesImpl] from now on.
     */
    private fun updateSourceDirectories(
        target: SourceDirectoriesImpl,
        sourceSet: AndroidSourceDirectorySet?,
    ) {
        if (sourceSet != null) {
            (sourceSet as DefaultAndroidSourceDirectorySet).addLateAdditionDelegate(target)
            for (srcDir in sourceSet.srcDirs) {
                target.addSource(
                    FileBasedDirectoryEntryImpl(
                        name = "variant",
                        directory = srcDir,
                        filter = sourceSet.filter,
                        // since it was part of the original set of sources for the module, we
                        // must add it back to the model as it is expecting to have variant sources.
                        shouldBeAddedToIdeModel = true
                    )
                )
            }
        }
    }
}
