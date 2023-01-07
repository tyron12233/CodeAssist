//package com.tyron.kotlin.completion.caches.resolve
//
//import org.jetbrains.kotlin.analyzer.AnalysisResult
//import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
//import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicatorProvider
//import org.jetbrains.kotlin.com.intellij.psi.PsiElement
//import org.jetbrains.kotlin.container.ComponentProvider
//import org.jetbrains.kotlin.container.get
//import org.jetbrains.kotlin.context.GlobalContext
//import org.jetbrains.kotlin.descriptors.ModuleDescriptor
//import org.jetbrains.kotlin.psi.KtElement
//import org.jetbrains.kotlin.psi.KtFile
//import org.jetbrains.kotlin.resolve.BodyResolveCache
//import org.jetbrains.kotlin.resolve.lazy.ResolveSession
//import org.jetbrains.kotlin.storage.CancellableSimpleLock
//import org.jetbrains.kotlin.utils.checkWithAttachment
//import java.util.concurrent.locks.ReentrantLock
//
//internal class PerFileAnalysisCache(val file: KtFile, componentProvider: ComponentProvider) {
//    private val globalContext = componentProvider.get<GlobalContext>()
//    private val moduleDescriptor = componentProvider.get<ModuleDescriptor>()
//    private val resolveSession = componentProvider.get<ResolveSession>()
//    private val codeFragmentAnalyzer = componentProvider.get<CodeFragmentAnalyzer>()
//    private val bodyResolveCache = componentProvider.get<BodyResolveCache>()
//
//    private val cache = HashMap<PsiElement, AnalysisResult>()
//    private var fileResult: AnalysisResult? = null
//    private val lock = ReentrantLock()
//    private val guardLock = CancellableSimpleLock(lock,
//        checkCancelled = {
//            ProgressIndicatorProvider.checkCanceled()
//        },
//        interruptedExceptionHandler = { throw ProcessCanceledException(it) })
//
//    private fun check(element: KtElement) {
//        checkWithAttachment(element.containingFile == file, {
//            "Expected $file, but was ${element.containingFile} for ${if (element.isValid) "valid" else "invalid"} $element "
//        }) {
//            it.withAttachment("element.kt", element.text)
//            it.withAttachment("file.kt", element.containingFile.text)
//            it.withAttachment("original.kt", file.text)
//        }
//    }
//
//    internal val isValid: Boolean get() = moduleDescriptor.isValid
//
//
//    internal fun fetchAnalysisResults(element: KtElement): AnalysisResult? {
//        check(element)
//
//        if (lock.tryLock()) {
//            try {
//                updateFileResultFromCache()
//
//                return fileResult?.takeIf { file.inBlockModifications.isEmpty() }
//            } finally {
//                lock.unlock()
//            }
//        }
//        return null
//    }
//
//    internal fun getAnalysisResults(element: KtElement): AnalysisResult {
//        check(element)
//
//        val analyzableParent = KotlinResolveDataProvider.findAnalyzableParent(element) ?: return AnalysisResult.EMPTY
//
//        return guardLock.guarded {
//            // step 1: perform incremental analysis IF it is applicable
//            getIncrementalAnalysisResult()?.let { return@guarded it }
//
//            // cache does not contain AnalysisResult per each kt/psi element
//            // instead it looks up analysis for its parents - see lookUp(analyzableElement)
//
//            // step 2: return result if it is cached
//            lookUp(analyzableParent)?.let {
//                return@guarded it
//            }
//
//            // step 3: perform analyze of analyzableParent as nothing has been cached yet
//            val result = analyze(analyzableParent)
//            cache[analyzableParent] = result
//
//            return@guarded result
//        }
//    }
//}