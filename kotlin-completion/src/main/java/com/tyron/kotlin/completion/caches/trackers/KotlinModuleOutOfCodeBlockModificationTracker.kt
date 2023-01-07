//package com.tyron.kotlin.completion.caches.trackers
//
//import org.jetbrains.kotlin.com.intellij.openapi.Disposable
//import org.jetbrains.kotlin.com.intellij.openapi.components.Service
//import org.jetbrains.kotlin.com.intellij.openapi.module.Module
//import org.jetbrains.kotlin.com.intellij.openapi.project.Project
//import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
//import org.jetbrains.kotlin.com.intellij.util.CommonProcessors
//import org.jetbrains.kotlin.psi.KtFile
//
//class KotlinModuleOutOfCodeBlockModificationTracker(private val module: Module) : ModificationTracker {
//
//    private val kotlinOutOfCodeBlockTracker = KotlinCodeBlockModificationListener.getInstance(module.project).kotlinOutOfCodeBlockTracker
//
//    private val updater
//        get() = getUpdaterInstance(module.project)
//
//    private val dependencies by lazy {
//        // Avoid implicit capturing for this to make CachedValueStabilityChecker happy
//        val module = module
//
//        module.cacheByClassInvalidatingOnRootModifications(KeyForCachedDependencies::class.java) {
//            HashSet<Module>().also { resultModuleSet ->
//                ModuleRootManager.getInstance(module).orderEntries().recursively().forEachModule(
//                    CommonProcessors.CollectProcessor(resultModuleSet)
//                )
//                resultModuleSet.addAll(
//                    ModuleDependencyProviderExtension.getInstance(module.project).getAdditionalDependencyModules(module)
//                )
//            }
//        }
//    }
//
//    object KeyForCachedDependencies
//
//    override fun getModificationCount(): Long {
//        val currentGlobalCount = kotlinOutOfCodeBlockTracker.modificationCount
//
//        if (updater.hasPerModuleModificationCounts()) {
//            val selfCount = updater.getModificationCount(module)
//            if (selfCount == currentGlobalCount) return selfCount
//
//            var maxCount = selfCount
//            for (dependency in dependencies) {
//                val depCount = updater.getModificationCount(dependency)
//                if (depCount == currentGlobalCount) return currentGlobalCount
//                if (depCount > maxCount) maxCount = depCount
//            }
//            return maxCount
//        }
//
//        return currentGlobalCount
//    }
//
//    companion object {
//        internal fun getUpdaterInstance(project: Project): Updater =
//            project.getServiceSafe()
//
//        fun getModificationCount(module: Module): Long = getUpdaterInstance(module.project).getModificationCount(module)
//    }
//
//    @Service
//    class Updater(private val project: Project): Disposable {
//        private val kotlinOfOfCodeBlockTracker
//            get() =
//                KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker
//
//        private val perModuleModCount = mutableMapOf<Module, Long>()
//
//        private var lastAffectedModule: Module? = null
//
//        private var lastAffectedModuleModCount = -1L
//
//        // All modifications since that count are known to be single-module modifications reflected in
//        // perModuleModCount map
//        private var perModuleChangesHighWatermark: Long? = null
//
//        internal fun getModificationCount(module: Module): Long {
//            return perModuleModCount[module] ?: perModuleChangesHighWatermark ?: kotlinOfOfCodeBlockTracker.modificationCount
//        }
//
//        internal fun hasPerModuleModificationCounts() = perModuleChangesHighWatermark != null
//
//        internal fun onKotlinPhysicalFileOutOfBlockChange(ktFile: KtFile, immediateUpdatesProcess: Boolean) {
//            lastAffectedModule = findModuleForPsiElement(ktFile)
//            lastAffectedModuleModCount = kotlinOfOfCodeBlockTracker.modificationCount
//
//            if (immediateUpdatesProcess) {
//                onPsiModificationTrackerUpdate(0)
//            }
//        }
//
//        internal fun onPsiModificationTrackerUpdate(customIncrement: Int = 0) {
//            val newModCount = kotlinOfOfCodeBlockTracker.modificationCount
//            val affectedModule = lastAffectedModule
//            if (affectedModule != null && newModCount == lastAffectedModuleModCount + customIncrement) {
//                if (perModuleChangesHighWatermark == null) {
//                    perModuleChangesHighWatermark = lastAffectedModuleModCount
//                }
//                perModuleModCount[affectedModule] = newModCount
//            } else {
//                // Some updates were not processed in our code so they probably came from other languages. Invalidate all.
//                clean()
//            }
//        }
//
//        private fun clean() {
//            perModuleChangesHighWatermark = null
//            lastAffectedModule = null
//            perModuleModCount.clear()
//        }
//
//        override fun dispose() = clean()
//    }
//}