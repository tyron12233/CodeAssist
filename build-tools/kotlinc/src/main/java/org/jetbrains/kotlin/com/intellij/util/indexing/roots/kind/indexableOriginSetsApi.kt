package org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.sdk.Sdk
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexableSetContributor

/**
 * Represents an origin of [com.intellij.util.indexing.roots.IndexableFilesIterator].
 */
interface IndexableSetOrigin

interface ModuleRootOrigin : IndexableSetOrigin {
    val module: org.jetbrains.kotlin.com.intellij.openapi.module.Module
    val roots: List<VirtualFile>
}

interface LibraryOrigin : IndexableSetOrigin {
    val classRoots: List<VirtualFile>
    val sourceRoots: List<VirtualFile>
}

interface SyntheticLibraryOrigin : IndexableSetOrigin {
//    val syntheticLibrary: SyntheticLibrary
    val rootsToIndex: Collection<VirtualFile>
}

interface SdkOrigin : IndexableSetOrigin {
    val sdk: Sdk
    val rootsToIndex: Collection<VirtualFile>
}

interface IndexableSetContributorOrigin : IndexableSetOrigin {
    val indexableSetContributor: IndexableSetContributor
    val rootsToIndex: Set<VirtualFile>
}

interface ProjectFileOrDirOrigin : IndexableSetOrigin {
    val fileOrDir: VirtualFile
}