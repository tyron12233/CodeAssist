package com.tyron.kotlin.completion.core

import com.tyron.builder.BuildModule
import com.tyron.builder.project.mock.MockAndroidModule
import com.tyron.builder.project.mock.MockFileManager
import com.tyron.common.TestUtil
import com.tyron.kotlin.completion.core.model.KotlinEnvironment
import com.tyron.kotlin.completion.core.resolve.KotlinAnalyzer
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState
import org.jetbrains.kotlin.com.intellij.openapi.command.WriteCommandAction
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

class KotlinAnalyzerTest {

//    @Before
//    fun setup() {
//        val androidJar =
//            File("""C:\Users\tyron scott\StudioProjects\CodeAssist\build-tools\build-tests\src\test\resources\bootstraps\rt.jar""")
//        BuildModule.setAndroidJar(androidJar)
//    }
//
//    @Test
//    fun test() {
//        val root = File("")
//        val kotlinModule = MockAndroidModule(root, MockFileManager(root))
//        kotlinModule.addJavaFile(File(TestUtil.getResourcesDirectory(), "Test.java"))
//        kotlinModule.apply {
//            open()
//            index()
//        }
//        val environment = KotlinEnvironment.getEnvironment(kotlinModule)
//
//        val psiFile = PsiFileFactory.getInstance(environment.project)
//            .createFileFromText(KotlinLanguage.INSTANCE, "fun test() { }")
//        val result = KotlinAnalyzer.analyzeFile(psiFile as KtFile)
//
//        val project = environment.project
//        val documentManager = PsiDocumentManager.getInstance(project)
//        val psiManager = PsiManager.getInstance(project)
//
//        val physicalPsiFile = psiManager.findFile(CoreLocalFileSystem().findFileByIoFile(File(TestUtil.getResourcesDirectory(), "Test.java"))!!)
//        val document = documentManager.getDocument(physicalPsiFile!!)
//        println()
    }
}