//package com.tyron.kotlin.completion.caches.trackers
//
//import org.jetbrains.kotlin.com.intellij.openapi.module.Module
//import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
//
//fun getLatestModificationCount(modules: Collection<Module>): Long {
//    if (modules.isEmpty())
//        return ModificationTracker.NEVER_CHANGED.modificationCount
//
//    val modificationCountUpdater =
//        KotlinModuleOutOfCodeBlockModificationTracker.getUpdaterInstance(modules.first().project)
//    return modules.maxOfOrNull { modificationCountUpdater.getModificationCount(it) }
//        ?: ModificationTracker.NEVER_CHANGED.modificationCount
//}