package com.tyron.kotlin.completion.core.model

import com.tyron.builder.BuildModule
import com.tyron.builder.project.api.AndroidModule
import com.tyron.builder.project.api.JavaModule
import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.resolve.lang.kotlin.CodeAssistVirtualFileFinderFactory
import org.jetbrains.kotlin.asJava.classes.FacadeCache
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector.Companion.NONE
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.config.CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS
import org.jetbrains.kotlin.config.CommonConfigurationKeys.MODULE_NAME
import org.jetbrains.kotlin.config.LanguageVersion.Companion.LATEST_STABLE
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import java.io.File
import java.nio.file.Path
import java.util.function.Function
import java.util.stream.Collectors

fun getEnvironment(module: KotlinModule): KotlinCoreEnvironment {
    return KotlinEnvironment.getEnvironment(module)
}

fun getEnvironment(project: org.jetbrains.kotlin.com.intellij.openapi.project.Project): KotlinCoreEnvironment? {
    val javaProject = KotlinEnvironment.getJavaProject(project)
    return javaProject?.let { KotlinEnvironment.getEnvironment(it) }
}

class KotlinEnvironment private constructor(val module: KotlinModule, disposable: Disposable) :
    KotlinCommonEnvironment(disposable) {

    val index by lazy { JvmDependenciesIndexImpl(getRoots().toList()) }

    init {
        registerProjectDependenServices(module)
        configureClasspath(module)

        with(project) {
            registerService(FacadeCache::class.java, FacadeCache(project))
        }

//        registerCompilerPlugins()

//        cachedEnvironment.putEnvironment(module, this)
    }

    private fun configureClasspath(kotlinModule: KotlinModule) {
        val androidJar = BuildModule.getAndroidJar()
        if (androidJar.exists()) {
            addToClassPath(androidJar)
        }

        if (kotlinModule is JavaModule) {
            addToClassPath(kotlinModule.javaDirectory)

            kotlinModule.libraries.filter {
                it.extension == "jar"
            }.forEach {
                addToClassPath(it)
            }
        }

        if (kotlinModule is AndroidModule) {
            val file = File(kotlinModule.buildDirectory, "injected/resource")
            if (file.exists()) {
                addToClassPath(file)
            }
        }
    }

    private fun registerProjectDependenServices(module: KotlinModule) {
        val finderFactory = CodeAssistVirtualFileFinderFactory(module)
        project.registerService(VirtualFileFinderFactory::class.java, finderFactory)
        project.registerService(MetadataFinderFactory::class.java, finderFactory)
//        project.registerService(KotlinLightClassManager::class.java, KotlinLightClassManager(javaProject.project))
    }

    companion object {
        private val cachedEnvironment = CachedEnvironment<KotlinModule, KotlinCoreEnvironment>()
        private val environmentCreation = { module: KotlinModule ->
            KotlinCoreEnvironment.createForProduction(
                Disposer.newDisposable("Project Env ${module.name}"),
                getConfiguration(module),
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
        }

        @JvmStatic
        fun getEnvironment(kotlinModule: KotlinModule): KotlinCoreEnvironment =
            cachedEnvironment.getOrCreateEnvironment(kotlinModule, environmentCreation)

        @JvmStatic
        fun removeEnvironment(kotlinModule: KotlinModule) {
            cachedEnvironment.removeEnvironment(kotlinModule)
//            KotlinPsiManager.invalidateCachedProjectSourceFiles()
//            KotlinAnalysisFileCache.resetCache()
//            KotlinAnalysisProjectCache.resetCache(eclipseProject)
        }

        @JvmStatic
        fun removeAllEnvironments() {
            cachedEnvironment.removeAllEnvironments()
//            KotlinPsiManager.invalidateCachedProjectSourceFiles()
//            KotlinAnalysisFileCache.resetCache()
//            KotlinAnalysisProjectCache.resetAllCaches()
        }

        @JvmStatic
        fun getJavaProject(project: org.jetbrains.kotlin.com.intellij.openapi.project.Project):
                KotlinModule? = cachedEnvironment.getEclipseResource(project)

    }

}

private fun getConfiguration(module: KotlinModule): CompilerConfiguration {
    val configuration = CompilerConfiguration()
    val map: HashMap<LanguageFeature, LanguageFeature.State> = HashMap()
    for (value in LanguageFeature.values()) {
        map[value] = LanguageFeature.State.ENABLED
    }
    val settings: LanguageVersionSettings = LanguageVersionSettingsImpl(
        LATEST_STABLE,
        ApiVersion.createByLanguageVersion(LATEST_STABLE),
        emptyMap(),
        map
    )
    configuration.put(MODULE_NAME, module.name)
    configuration.put(LANGUAGE_VERSION_SETTINGS, settings)
    configuration.put(
        CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
        NONE
    )
    configuration.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)
    configuration.put(JVMConfigurationKeys.NO_JDK, true)

    configuration.addJvmClasspathRoot(BuildModule.getAndroidJar())

    if (module is JavaModule) {
        configuration.addJavaSourceRoot(module.javaDirectory)

        module.libraries.forEach(configuration::addJvmClasspathRoot)
    }
    return configuration
}