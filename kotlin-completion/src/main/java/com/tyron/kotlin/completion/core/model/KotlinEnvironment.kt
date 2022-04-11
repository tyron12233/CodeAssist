package com.tyron.kotlin.completion.core.model

import com.tyron.builder.BuildModule
import com.tyron.builder.project.api.AndroidModule
import com.tyron.builder.project.api.JavaModule
import com.tyron.builder.project.api.KotlinModule
import com.tyron.completion.progress.ProgressManager
import com.tyron.kotlin.completion.core.resolve.lang.kotlin.CodeAssistVirtualFileFinderFactory
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.kotlin.asJava.classes.FacadeCache
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector.Companion.NONE
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.toAbstractProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmSdkRoots
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.application.*
import org.jetbrains.kotlin.com.intellij.openapi.components.ServiceManager
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener
import org.jetbrains.kotlin.com.intellij.psi.impl.DocumentCommitProcessor
import org.jetbrains.kotlin.com.intellij.psi.impl.DocumentCommitThread
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeCopyHandler
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.config.CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS
import org.jetbrains.kotlin.config.CommonConfigurationKeys.MODULE_NAME
import org.jetbrains.kotlin.config.LanguageVersion.Companion.LATEST_STABLE
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.stream.Collectors
import kotlin.reflect.jvm.javaConstructor

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
        configureClasspath(module)

        with(project) {
            registerService(FacadeCache::class.java, FacadeCache(project))
        }
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

    companion object {
        private val cachedEnvironment = CachedEnvironment<KotlinModule, KotlinCoreEnvironment>()
        private val environmentCreation = { module: KotlinModule ->
            val environment = KotlinCoreEnvironment.createForProduction(
                Disposer.newDisposable("Project Env ${module.name}"),
                getConfiguration(module),
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )

            environment.addKotlinSourceRoots(listOf(module.kotlinDirectory, (module as JavaModule).javaDirectory))

            CoreApplicationEnvironment.registerApplicationExtensionPoint(DocumentWriteAccessGuard.EP_NAME, DocumentWriteAccessGuard::class.java);
            environment.projectEnvironment.registerProjectExtensionPoint(
                ExtensionPointName.create(PsiTreeChangeListener.EP.name),
                PsiTreeChangeListener::class.java
            )

            (environment.projectEnvironment.environment as CoreApplicationEnvironment)
                .registerApplicationService(AsyncExecutionService::class.java, object : AsyncExecutionService() {

                    val executor = Executors.newSingleThreadExecutor()

                    override fun createWriteThreadExecutor(p0: ModalityState): AppUIExecutor {
                        return object : AppUIExecutor {
                            override fun expireWith(p0: Disposable): AppUIExecutor {
                                TODO("Not yet implemented")
                            }

                            override fun submit(p0: Runnable): CancellablePromise<*> {
                                val result = executor.submit(p0)
                                return CancellablePromiseWrapper(result)
                            }

                            override fun later(): AppUIExecutor {
                                TODO("Not yet implemented")
                            }

                        }
                    }

                    override fun <T : Any?> buildNonBlockingReadAction(callable: Callable<T>): NonBlockingReadAction<T> {
                        return NonBlockingReadActionImpl(callable)
                    }

                })

            val newInstance = DocumentCommitThread::class.constructors.first()
                .javaConstructor?.newInstance();
            (environment.projectEnvironment.environment as CoreApplicationEnvironment)
                .registerApplicationService(DocumentCommitProcessor::class.java, newInstance);
            environment.projectEnvironment.project.picoContainer.unregisterComponent(PsiDocumentManager::class.java.name)

            registerProjectDependentServices(module, environment.project as MockProject)
            environment
        }

        private fun registerProjectDependentServices(module: KotlinModule, project: MockProject) {
            project.registerService(PsiNameHelper::class.java, PsiNameHelperImpl(project))
            project.registerService(PsiDocumentManager::class.java, object : PsiDocumentManagerBase(project) {

            })
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

    configuration.addJvmSdkRoots(listOf(BuildModule.getAndroidJar()))

    if (module is JavaModule) {
        configuration.addJavaSourceRoot(module.javaDirectory)
        configuration.addJvmClasspathRoots(module.libraries)
    }

    if (module is AndroidModule) {
        configuration.addJavaSourceRoot(File("${module.buildDirectory}/injected"))
    }

    return configuration
}