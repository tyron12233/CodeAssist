package com.tyron.builder.gradle.internal.api

import com.tyron.builder.gradle.api.AndroidSourceDirectorySet
import com.tyron.builder.gradle.api.AndroidSourceFile
import com.tyron.builder.gradle.api.AndroidSourceSet
import com.tyron.builder.gradle.internal.api.artifact.SourceArtifactType
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_ANNOTATION_PROCESSOR
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_API
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_APK
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_COMPILE
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_COMPILE_ONLY
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_IMPLEMENTATION
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_PROVIDED
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_PUBLISH
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_RUNTIME_ONLY
import com.tyron.builder.gradle.internal.dependency.VariantDependencies.Companion.CONFIG_NAME_WEAR_APP
import com.tyron.builder.gradle.internal.ide.CustomSourceDirectoryImpl
import com.tyron.builder.internal.utils.appendCapitalized
import com.tyron.builder.model.SourceProvider
import com.tyron.builder.model.v2.CustomSourceDirectory
import com.android.SdkConstants
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.util.ConfigureUtil
import org.gradle.util.GUtil
import java.io.File
import javax.inject.Inject

open class DefaultAndroidSourceSet @Inject constructor(
    private val name: String,
    project: Project,
    private val publishPackage: Boolean
) : AndroidSourceSet, SourceProvider {

    final override val java: AndroidSourceDirectorySet
    final override val kotlin: com.tyron.builder.api.dsl.AndroidSourceDirectorySet
    final override val resources: AndroidSourceDirectorySet
    final override val manifest: AndroidSourceFile
    final override val assets: AndroidSourceDirectorySet
    final override val res: AndroidSourceDirectorySet
    final override val aidl: AndroidSourceDirectorySet
    final override val renderscript: AndroidSourceDirectorySet
    @Deprecated("Unused")
    final override val jni: AndroidSourceDirectorySet
    final override val jniLibs: AndroidSourceDirectorySet
    final override val shaders: AndroidSourceDirectorySet
    final override val mlModels: AndroidSourceDirectorySet
    private val displayName : String = GUtil.toWords(this.name)

    init {
        java = DefaultAndroidSourceDirectorySet(
            displayName, "Java source", project, SourceArtifactType.JAVA_SOURCES
        )
        java.filter.include("**/*.java")

        kotlin = DefaultAndroidSourceDirectorySet(
                displayName, "Kotlin source", project, SourceArtifactType.KOTLIN_SOURCES
        )
        kotlin.filter.include("**/*.kt", "**/*.kts")

        resources = DefaultAndroidSourceDirectorySet(
            displayName,
            "Java resources",
            project,
            SourceArtifactType.JAVA_RESOURCES
        )
        resources.filter.exclude("**/*.java", "**/*.kt")

        manifest = DefaultAndroidSourceFile("$displayName manifest", project)

        assets = DefaultAndroidSourceDirectorySet(
            displayName, "assets", project, SourceArtifactType.ASSETS
        )

        res = DefaultAndroidSourceDirectorySet(
            displayName,
            "resources",
            project,
            SourceArtifactType.ANDROID_RESOURCES
        )

        aidl = DefaultAndroidSourceDirectorySet(
            displayName, "aidl", project, SourceArtifactType.AIDL
        )

        renderscript = DefaultAndroidSourceDirectorySet(
            displayName,
            "renderscript",
            project,
            SourceArtifactType.RENDERSCRIPT
        )

        jni = DefaultAndroidSourceDirectorySet(
            displayName, "jni", project, SourceArtifactType.JNI
        )

        jniLibs = DefaultAndroidSourceDirectorySet(
            displayName, "jniLibs", project, SourceArtifactType.JNI_LIBS
        )

        shaders = DefaultAndroidSourceDirectorySet(
            displayName, "shaders", project, SourceArtifactType.SHADERS
        )

        mlModels = DefaultAndroidSourceDirectorySet(
            displayName, "ML models", project, SourceArtifactType.ML_MODELS
        )

        initRoot("src/$name")
    }

    override fun getName(): String {
        return name
    }

    override fun toString(): String {
        return "source set $displayName"
    }

    private fun getName(config: String): String {
        return if (name == SourceSet.MAIN_SOURCE_SET_NAME) {
            config
        } else {
            name.appendCapitalized(config)
        }
    }

    override val apiConfigurationName: String
        get() = getName(CONFIG_NAME_API)

    override val compileOnlyConfigurationName: String
        get() = getName(CONFIG_NAME_COMPILE_ONLY)

    override val implementationConfigurationName: String
        get() = getName(CONFIG_NAME_IMPLEMENTATION)

    override val runtimeOnlyConfigurationName: String
        get() = getName(CONFIG_NAME_RUNTIME_ONLY)

    @Suppress("OverridingDeprecatedMember")
    override val compileConfigurationName: String
        get() = getName(CONFIG_NAME_COMPILE)

    @Suppress("OverridingDeprecatedMember")
    override val packageConfigurationName: String
        get() {
        if (publishPackage) {
            return getName(CONFIG_NAME_PUBLISH)
        }

        return getName(CONFIG_NAME_APK)
    }

    @Suppress("OverridingDeprecatedMember")
    override val providedConfigurationName = getName(CONFIG_NAME_PROVIDED)

    override val wearAppConfigurationName = getName(CONFIG_NAME_WEAR_APP)

    override val annotationProcessorConfigurationName
    get()
            = getName(CONFIG_NAME_ANNOTATION_PROCESSOR)

    override fun manifest(action: com.tyron.builder.api.dsl.AndroidSourceFile.() -> Unit) {
        action.invoke(manifest)
    }

    override fun manifest(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, manifest)
        return this
    }

    override fun res(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(res)
    }

    override fun res(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, res)
        return this
    }

    override fun assets(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(assets)
    }

    override fun assets(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, assets)
        return this
    }

    override fun aidl(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(aidl)
    }

    override fun aidl(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, aidl)
        return this
    }

    override fun renderscript(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(renderscript)
    }

    override fun renderscript(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, renderscript)
        return this
    }

    override fun jni(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(jni)
    }

    override fun jni(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, jni)
        return this
    }

    override fun jniLibs(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(jniLibs)
    }

    override fun jniLibs(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, jniLibs)
        return this
    }

    override fun shaders(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(shaders)
    }

    override fun shaders(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, shaders)
        return this
    }

    override fun mlModels(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(mlModels)
    }

    override fun mlModels(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, mlModels)
        return this
    }

    override fun java(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(java)
    }

    override fun java(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, java)
        return this
    }

    override fun kotlin(action: Action<com.tyron.builder.api.dsl.AndroidSourceDirectorySet>) {
        action.execute(kotlin)
    }

    override fun resources(action: com.tyron.builder.api.dsl.AndroidSourceDirectorySet.() -> Unit) {
        action.invoke(resources)
    }

    override fun resources(configureClosure: Closure<*>): AndroidSourceSet {
        ConfigureUtil.configure(configureClosure, resources)
        return this
    }

    override fun setRoot(path: String): AndroidSourceSet {
        return initRoot(path)
    }

    private fun initRoot(path: String): AndroidSourceSet {
        java.setSrcDirs(listOf("$path/java"))
        kotlin.setSrcDirs(listOf("$path/java", "$path/kotlin"))
        resources.setSrcDirs(listOf("$path/resources"))
        res.setSrcDirs(listOf("$path/${SdkConstants.FD_RES}"))
        assets.setSrcDirs(listOf("$path/${SdkConstants.FD_ASSETS}"))
        manifest.srcFile("$path/${SdkConstants.FN_ANDROID_MANIFEST_XML}")
        aidl.setSrcDirs(listOf("$path/aidl"))
        renderscript.setSrcDirs(listOf("$path/rs"))
        jni.setSrcDirs(listOf("$path/jni"))
        jniLibs.setSrcDirs(listOf("$path/jniLibs"))
        shaders.setSrcDirs(listOf("$path/shaders"))
//        mlModels.setSrcDirs(listOf("$path/${SdkConstants.FD_ML_MODELS}"))
        return this
    }

    // --- SourceProvider

    override fun getJavaDirectories(): Set<File> {
        return java.srcDirs
    }

    override fun getKotlinDirectories(): Set<File> {
        return (kotlin as DefaultAndroidSourceDirectorySet).srcDirs
    }

    override fun getResourcesDirectories(): Set<File> {
        return resources.srcDirs
    }

    override fun getManifestFile(): File {
        return manifest.srcFile
    }

    override fun getAidlDirectories(): Set<File> {
        return aidl.srcDirs
    }

    override fun getRenderscriptDirectories(): Set<File> {
        return renderscript.srcDirs
    }

    override fun getCDirectories(): Set<File> {
        return jni.srcDirs
    }

    override fun getCppDirectories(): Set<File> {
        // The C and C++ directories are currently the same.  This may change in the future when
        // we use Gradle's native source sets.
        return jni.srcDirs
    }

    override fun getResDirectories(): Set<File> {
        return res.srcDirs
    }

    override fun getAssetsDirectories(): Set<File> {
        return assets.srcDirs
    }

    override fun getJniLibsDirectories(): Collection<File> {
        return jniLibs.srcDirs
    }

    override fun getShadersDirectories(): Collection<File> {
        return shaders.srcDirs
    }

    override fun getMlModelsDirectories(): Collection<File> {
        return mlModels.srcDirs
    }

    // needed for IDE model implementation, not part of the DSL.
    override fun getCustomDirectories(): List<CustomSourceDirectory> {
        return extras.asMap.values.groupBy(
            DefaultAndroidSourceDirectorySet::getSourceSetName,
            DefaultAndroidSourceDirectorySet::srcDirs
        ).map {
            // there can be only one directory per source set since we do not allow to have
            // access to the extras field to end users (see below).
            CustomSourceDirectoryImpl(
                it.key,
                it.value.flatten().single(),
            )
        }
    }

    /**
     * Internal API to add customs folders to the DSL. Custom folders cannot be added through the
     * public [com.tyron.builder.api.dsl.AndroidSourceSet] interface because it would allow users
     * to add a custom directory for a specific source set like 'main' or 'debug'.
     *
     * Since users cannot add have access to the created [DefaultAndroidSourceDirectorySet] for the
     * source types, they cannot add to it. Therefore there can only be one source directory per
     * custom source type on an Android Source Set.
     *
     * Users need to the use the public [AndroidComponents.registerSourceSet] API so
     * custom sources are added to all the source sets of the application uniformly.
     */
    internal val extras: NamedDomainObjectContainer<DefaultAndroidSourceDirectorySet> =
        project.objects.domainObjectContainer(
            DefaultAndroidSourceDirectorySet::class.java,
            AndroidSourceDirectorySetFactory(project, displayName, name)
        )

    class AndroidSourceDirectorySetFactory(
        private val project: Project,
        private val sourceSetDisplayName: String,
        private val sourceSetName: String,
    ): NamedDomainObjectFactory<DefaultAndroidSourceDirectorySet> {

        override fun create(name: String): DefaultAndroidSourceDirectorySet {
            return DefaultAndroidSourceDirectorySet(
                sourceSetDisplayName,
                name,
                project,
                SourceArtifactType.CUSTOMS).also {
                    it.srcDir("src/$sourceSetName/$name")
            }
        }
    }
}