package com.tyron.code

import com.tyron.code.ui.editor.DocumentContentSynchronizer
import io.github.rosemoe.sora.text.Content
import org.jetbrains.kotlin.com.intellij.core.CoreFileTypeRegistry
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuard
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuardImpl
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl
import org.junit.After
import org.junit.Before
import org.junit.Test

class DocumentTest {

    private val disposable = Disposer.newDisposable()
    private lateinit var project: MockProject

    @Before
    fun setup() {
        val application = object : MockApplication(disposable) {
            override fun isDispatchThread(): Boolean {
                return true
            }
        }

        application.registerService(TransactionGuard::class.java, TransactionGuardImpl())
        application.registerService(CommandProcessor::class.java, object : CommandProcessor() {

            private var currentCommand: Runnable? = null

            override fun executeCommand(p0: Project?, command: Runnable, p2: String?, p3: Any?) {
                try {
                    currentCommand = command
                    command.run()
                } finally {
                    currentCommand = null
                }
            }

            override fun getCurrentCommand(): Runnable? {
                return currentCommand
            }

            override fun isUndoTransparentActionInProgress(): Boolean {
                return true
            }
        })
        application.registerService(FileDocumentManager::class.java, FileDocumentManagerImpl())

        application.extensionArea.registerExtensionPoint(
            DocumentWriteAccessGuard.EP_NAME.name,
            DocumentWriteAccessGuard::class.qualifiedName!!,
            ExtensionPoint.Kind.INTERFACE
        )

        ApplicationManager.setApplication(application,
            { CoreFileTypeRegistry() }, disposable
        )

        project = MockProject(application.picoContainer, disposable)
        project.registerService(PsiManager::class.java, PsiManagerImpl(project))
        project.registerService(PsiDocumentManager::class.java, object: PsiDocumentManagerBase(project) {
            override fun removeListener(p0: Listener) {

            }

            override fun performLaterWhenAllCommitted(p0: Runnable, p1: ModalityState?) {

            }

        })
    }

    private val content: Content = Content("")
    private val document = DocumentImpl("")
    private val synchronizer by lazy {
        DocumentContentSynchronizer(
            project,
            document,
            content
        )
    }

    @Test
    fun `test document changes reflects to content properly`() {
        synchronizer.start()

        //
        document.insertString(0, "Hello World!")
        assert(content.contentEquals(document.text))

        document.deleteString(0, "Hello ".length)
        assert(content.contentEquals(document.text))

        document.insertString(document.textLength, " ")
        assert(content.contentEquals(document.text))

        document.deleteString(document.textLength - 1, document.textLength)
        assert(content.contentEquals(document.text))

        document.replaceString(0, document.textLength, document.text.uppercase())
        assert(content.contentEquals(document.text))

        document.deleteString(0, document.textLength)

        synchronizer.stop()
    }

    @Test
    fun `test content changes reflects to document properly`() {
        synchronizer.start()

        content.insert(0, 0, "Hello World")
        assert(document.text.contentEquals(content))

        val rangeMarker = document.createRangeMarker(document.textLength - 1, document.textLength, true)
        print(rangeMarker)

        content.delete(0, "Hello ".length)
        assert(document.text.contentEquals(content))

        println(rangeMarker)


        content.replace(0, content.length, content.toString().uppercase())
        assert(document.text.contentEquals(content))

        synchronizer.stop()
    }

    @After
    fun dispose() {
        Disposer.dispose(disposable)
    }
}