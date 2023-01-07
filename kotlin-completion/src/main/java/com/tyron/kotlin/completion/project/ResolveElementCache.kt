//package com.tyron.kotlin.completion.project
//
//import com.tyron.kotlin.completion.caches.resolve.CodeFragmentAnalyzer
//import org.jetbrains.kotlin.com.intellij.openapi.project.Project
//import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
//import org.jetbrains.kotlin.com.intellij.psi.util.CachedValue
//import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider
//import org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager
//import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil
//import org.jetbrains.kotlin.psi.KtDeclaration
//import org.jetbrains.kotlin.psi.KtElement
//import org.jetbrains.kotlin.psi.KtSuperTypeList
//import org.jetbrains.kotlin.psi.getModificationStamp
//import org.jetbrains.kotlin.resolve.BindingContext
//import org.jetbrains.kotlin.resolve.BodyResolveCache
//import org.jetbrains.kotlin.resolve.lazy.ResolveSession
//import kotlin.reflect.jvm.internal.impl.platform.TargetPlatform
//
//class ResolveElementCache(
//    private val resolveSession: ResolveSession,
//    private val project: Project,
//    private val targetPlatform: TargetPlatform,
//    private val codeFragmentAnalyzer: CodeFragmentAnalyzer
//) : BodyResolveCache {
//
//    private val forcedFullResolveOnHighlighting = Registry.`is`("kotlin.resolve.force.full.resolve.on.highlighting", true)
//
//    private class CachedFullResolve(val bindingContext: BindingContext, resolveElement: KtElement) {
//        private val modificationStamp: Long? = modificationStamp(resolveElement)
//
//        fun isUpToDate(resolveElement: KtElement) = modificationStamp == modificationStamp(resolveElement)
//
//        private fun modificationStamp(resolveElement: KtElement): Long? {
//            val file = resolveElement.containingFile
//            return when {
//                // for non-physical file we don't get OUT_OF_CODE_BLOCK_MODIFICATION_COUNT increased and must reset
//                // data on any modification of the file
//                !file.isPhysical -> file.modificationStamp
//
//                resolveElement is KtDeclaration && PureKotlinCodeBlockModificationListener.isBlockDeclaration(resolveElement) -> resolveElement.getModificationStamp()
//                resolveElement is KtSuperTypeList -> resolveElement.modificationStamp
//                else -> null
//            }
//        }
//    }
//
//    // drop whole cache after change "out of code block", each entry is checked with own modification stamp
//    private val fullResolveCache: CachedValue<MutableMap<KtElement, CachedFullResolve>> =
//        CachedValuesManager.getManager(project).createCachedValue(
//            CachedValueProvider {
//                CachedValueProvider.Result.create(
//                    ContainerUtil.createConcurrentWeakKeySoftValueMap(),
//                    KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker,
//                    resolveSession.exceptionTracker,
//                    rootsChangedTracker
//                )
//            },
//            false
//        )
//}