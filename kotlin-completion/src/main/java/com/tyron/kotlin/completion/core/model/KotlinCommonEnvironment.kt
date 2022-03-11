package com.tyron.kotlin.completion.core.model

import com.tyron.kotlin.completion.core.resolve.KotlinSourceIndex
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.codegen.extensions.ClassBuilderInterceptorExtension
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.com.intellij.codeInsight.ContainerProvider
import org.jetbrains.kotlin.com.intellij.codeInsight.ExternalAnnotationsManager
import org.jetbrains.kotlin.com.intellij.codeInsight.InferredAnnotationsManager
import org.jetbrains.kotlin.com.intellij.codeInsight.runner.JavaMainMethodProvider
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.core.CoreJavaFileManager
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment
import org.jetbrains.kotlin.com.intellij.lang.MetaLanguage
import org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.components.ServiceManager
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionsArea
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.PlainTextFileType
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider
import org.jetbrains.kotlin.com.intellij.psi.compiled.ClassFileDecompilers
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.extensions.DeclarationAttributeAltererExtension
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.resolve.CodeAnalyzerInitializer
import org.jetbrains.kotlin.resolve.ModuleAnnotationsResolver
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.resolve.jvm.diagnostics.DefaultErrorMessagesJvm
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver
import java.io.File
import kotlin.reflect.KClass

abstract class KotlinCommonEnvironment(disposable: Disposable) {
    val project: MockProject

    val projectEnvironment: JavaCoreProjectEnvironment
    private val roots = LinkedHashSet<JavaRoot>()

    val configuration = CompilerConfiguration()

    init {
        setIdeaIoUseFallback()

        projectEnvironment = object : JavaCoreProjectEnvironment(disposable, kotlinCoreApplicationEnvironment) {
            override fun preregisterServices() {
                registerProjectExtensionPoints(project.extensionArea)
                CoreApplicationEnvironment.registerExtensionPoint(
                    project.extensionArea,
                    JvmElementProvider.EP_NAME,
                    JvmElementProvider::class.java
                )
            }

            override fun createCoreFileManager(): JavaFileManager {
                return KotlinCliJavaFileManagerImpl(PsiManager.getInstance(project))
            }
        }

        project = projectEnvironment.project
        DeclarationAttributeAltererExtension.registerExtensionPoint(project)
        StorageComponentContainerContributor.registerExtensionPoint(project)

        with(project) {
            registerService(ModuleVisibilityManager::class.java, CliModuleVisibilityManagerImpl(true))

            registerService(
                CoreJavaFileManager::class.java,
                ServiceManager.getService(project, JavaFileManager::class.java) as CoreJavaFileManager
            )

            registerService(ModuleAnnotationsResolver::class.java, CliModuleAnnotationsResolver())
            registerService(KotlinSourceIndex::class.java, KotlinSourceIndex())
            registerService(ExternalAnnotationsManager::class.java, MockExternalAnnotationsManager())
            registerService(InferredAnnotationsManager::class.java, MockInferredAnnotationsManager())

            // todo: code style manager

            val traceHolder = CliTraceHolder().also {
                registerService(CodeAnalyzerInitializer::class.java, it)
            }

            CliLightClassGenerationSupport(traceHolder, this).also {
                registerService(LightClassGenerationSupport::class.java, it)
                registerService(CliLightClassGenerationSupport::class.java, it)
            }

            registerService(JavaModuleResolver::class.java, CodeAssistKotlinModuleResolver())

            val javaFileManager = ServiceManager.getService(this, JavaFileManager::class.java)
            (javaFileManager as KotlinCliJavaFileManagerImpl)
                .initialize(
                    JvmDependenciesDynamicCompoundIndex(),
                    arrayListOf(),
                    SingleJavaFileRootsIndex(arrayListOf()),
                    true
                )

            val area = this.extensionArea
            area.getExtensionPoint(PsiElementFinder.EP_NAME)
                .registerExtension(PsiElementFinderImpl(this, javaFileManager), disposable)
            val kotlinAsJavaSupport = CliKotlinAsJavaSupport(this, traceHolder)
            registerService(KotlinAsJavaSupport::class.java, kotlinAsJavaSupport)
            area.getExtensionPoint(PsiElementFinder.EP_NAME)
                .registerExtension(JavaElementFinder(this), disposable)
            registerService(KotlinJavaPsiFacade::class.java, KotlinJavaPsiFacade(this))
        }

        configuration.put(CommonConfigurationKeys.MODULE_NAME, project.name)

        ExpressionCodegenExtension.Companion.registerExtensionPoint(project)
        registerApplicationExtensionPointsAndExtensionsFrom()

        ClassBuilderInterceptorExtension.registerExtensionPoint(project)
    }

    fun getRoots(): Set<JavaRoot> = roots

    fun getVirtualFile(location: File): VirtualFile? {
        return kotlinCoreApplicationEnvironment.localFileSystem.findFileByIoFile(location)
    }

    fun getVirtualFileInJar(jarFile: File, relativePath: String): VirtualFile? {
        return kotlinCoreApplicationEnvironment.jarFileSystem.findFileByPath("${jarFile.absolutePath}!/$relativePath")
    }

    fun isJarFile(jarFile: File): Boolean {
        val jar = kotlinCoreApplicationEnvironment.jarFileSystem.findFileByPath(jarFile.absolutePath)
        return jar != null && jar.isValid
    }

    protected fun addToClassPath(path: File, rootType: JavaRoot.RootType? = null) {
        if (path.isFile) {
            val jarFile = kotlinCoreApplicationEnvironment.jarFileSystem.findFileByPath("$path!/")
                ?: return

            projectEnvironment.addJarToClassPath(path)

            val type = rootType ?: JavaRoot.RootType.BINARY
            roots.add(JavaRoot(jarFile, type))
        } else {
            val root = kotlinCoreApplicationEnvironment.localFileSystem.findFileByPath(path.absolutePath)
                ?: return

            projectEnvironment.addSourcesToClasspath(root)

            val type = rootType ?: JavaRoot.RootType.SOURCE
            roots.add(JavaRoot(root, type))
        }
    }

    companion object {
        val kotlinCoreApplicationEnvironment: KotlinCoreApplicationEnvironment by lazy {
            createKotlinCoreApplicationEnvironment(Disposer.newDisposable("Root Disposable"))
        }
    }
}

private fun createKotlinCoreApplicationEnvironment(disposable: Disposable): KotlinCoreApplicationEnvironment =
    KotlinCoreApplicationEnvironment.create(disposable, false).apply {
        registerAppExtensionPoints()

        registerFileType(PlainTextFileType.INSTANCE, "xml")
        registerFileType(KotlinFileType.INSTANCE, "kt")
        registerFileType(KotlinFileType.INSTANCE, KotlinParserDefinition.STD_SCRIPT_SUFFIX)
        registerParserDefinition(KotlinParserDefinition())

        // TODO: register other services
        application.registerService(KotlinBinaryClassCache::class.java, KotlinBinaryClassCache())
    }

private fun registerProjectExtensionPoints(area: ExtensionsArea) {
    registerExtensionPoint(area, PsiTreeChangePreprocessor.EP_NAME, PsiTreeChangePreprocessor::class)
    registerExtensionPoint(area, PsiElementFinder.EP_NAME, PsiElementFinder::class)
    registerExtensionPoint(area, SyntheticResolveExtension.extensionPointName, SyntheticResolveExtension::class)
}

fun registerApplicationExtensionPointsAndExtensionsFrom() {
    val EP_ERROR_MSGS =
        ExtensionPointName.create<DefaultErrorMessages.Extension>("org.jetbrains.defaultErrorMessages.extension")
    registerExtensionPointInRoot(DiagnosticSuppressor.EP_NAME, DiagnosticSuppressor::class)
    registerExtensionPointInRoot(EP_ERROR_MSGS, DefaultErrorMessages.Extension::class)

//    registerExtensionPointInRoot(CodeStyleSettingsProvider.EXTENSION_POINT_NAME, KotlinSettingsProvider::class)
//    registerExtensionPointInRoot(
//        LanguageCodeStyleSettingsProvider.EP_NAME,
//        KotlinLanguageCodeStyleSettingsProvider::class
//    )
    registerExtensionPointInRoot(JavaModuleSystem.EP_NAME, JavaModuleSystem::class)

    with(Extensions.getRootArea()) {
        getExtensionPoint(EP_ERROR_MSGS).registerExtension(DefaultErrorMessagesJvm())
//        getExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME).registerExtension(KotlinSettingsProvider())
//        getExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME).registerExtension(
//            KotlinLanguageCodeStyleSettingsProvider()
//        )
    }
}

private fun registerAppExtensionPoints() {
    registerExtensionPointInRoot(ContainerProvider.EP_NAME, ContainerProvider::class)
    registerExtensionPointInRoot(ClsCustomNavigationPolicy.EP_NAME, ClsCustomNavigationPolicy::class)
    registerExtensionPointInRoot(ClassFileDecompilers.getInstance().EP_NAME, ClassFileDecompilers.Decompiler::class)

    registerExtensionPointInRoot(PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class)
    registerExtensionPointInRoot(JavaMainMethodProvider.EP_NAME, JavaMainMethodProvider::class)

    CoreApplicationEnvironment.registerExtensionPoint(
        Extensions.getRootArea(),
        MetaLanguage.EP_NAME,
        MetaLanguage::class.java
    )
}

private fun <T : Any> registerExtensionPoint(
    area: ExtensionsArea,
    extensionPointName: ExtensionPointName<T>,
    aClass: KClass<out T>
) {
    CoreApplicationEnvironment.registerExtensionPoint(area, extensionPointName, aClass.java)
}

private fun <T : Any> registerExtensionPointInRoot(extensionPointName: ExtensionPointName<T>, aClass: KClass<out T>) {
    registerExtensionPoint(Extensions.getRootArea(), extensionPointName, aClass)
}