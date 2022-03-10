package com.tyron.kotlin.completion.core.model

import com.tyron.builder.project.Project
import com.tyron.builder.project.api.JavaModule
import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.resolve.lang.kotlin.CodeAssistVirtualFileFinder
import com.tyron.kotlin.completion.core.resolve.lang.kotlin.CodeAssistVirtualFileFinderFactory
import org.jetbrains.kotlin.asJava.classes.FacadeCache
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory

fun getEnvironment(module: KotlinModule): KotlinCommonEnvironment {
    return KotlinEnvironment.getEnvironment(module)
}

fun getEnvironment(project: org.jetbrains.kotlin.com.intellij.openapi.project.Project): KotlinEnvironment? {
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

        cachedEnvironment.putEnvironment(module, this)
    }

    private fun configureClasspath(kotlinModule: KotlinModule) {
        if (kotlinModule is JavaModule) {
            kotlinModule.javaFiles.forEach {
                addToClassPath(it.value)
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
        private val cachedEnvironment = CachedEnvironment<KotlinModule, KotlinEnvironment>()
        private val environmentCreation = { module: KotlinModule ->
            KotlinEnvironment(module, Disposer.newDisposable("Project Env ${module.name}"))
        }

        @JvmStatic
        fun getEnvironment(kotlinModule: KotlinModule): KotlinEnvironment =
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