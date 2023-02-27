package org.jetbrains.kotlin.com.intellij.util.indexing.projectFilter

import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

enum class FileAddStatus {
  ADDED, PRESENT, SKIPPED
}

sealed class ProjectIndexableFilesFilterHolder {
  abstract fun getProjectIndexableFiles(project: Project): IdFilter?

  abstract fun addFileId(fileId: Int, projects: () -> Set<Project>): FileAddStatus

  abstract fun addFileId(fileId: Int, project: Project): FileAddStatus

  abstract fun entireProjectUpdateStarted(project: Project)

  abstract fun entireProjectUpdateFinished(project: Project)

  abstract fun removeFile(fileId: Int)

  abstract fun findProjectForFile(fileId: Int): Project?

  abstract fun runHealthCheck()
}

internal class IncrementalProjectIndexableFilesFilterHolder : ProjectIndexableFilesFilterHolder() {
  private val myProjectFilters: ConcurrentMap<Project, IncrementalProjectIndexableFilesFilter> = ConcurrentHashMap()

  init {
//    ApplicationManager.getApplication().messageBus.connect().subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
//      override fun projectClosed(project: Project) {
//        myProjectFilters.remove(project)
//      }
//    })
  }

  override fun getProjectIndexableFiles(project: Project): IdFilter? {
//    if (!UnindexedFilesScanner.isProjectContentFullyScanned(project) || UnindexedFilesUpdater.isIndexUpdateInProgress(project)) {
//      return null
//    }
    return getFilter(project)
  }

  override fun entireProjectUpdateStarted(project: Project) {
//    assert(UnindexedFilesUpdater.isIndexUpdateInProgress(project))

    getFilter(project)?.memoizeAndResetFileIds()
  }

  override fun entireProjectUpdateFinished(project: Project) {
//    assert(UnindexedFilesUpdater.isIndexUpdateInProgress(project))

    getFilter(project)?.resetPreviousFileIds()
  }

  private fun getFilter(project: Project) = myProjectFilters.computeIfAbsent(project) {
    if (it.isDisposed) null else IncrementalProjectIndexableFilesFilter()
  }

  override fun addFileId(fileId: Int, projects: () -> Set<Project>): FileAddStatus {
    val matchedProjects by lazy(LazyThreadSafetyMode.NONE) { projects() }
    val statuses = myProjectFilters.map { (p, filter) ->
      filter.ensureFileIdPresent(fileId) {
        matchedProjects.contains(p)
      }
    }

    if (statuses.all { it == FileAddStatus.SKIPPED }) return FileAddStatus.SKIPPED
    if (statuses.any { it == FileAddStatus.ADDED }) return FileAddStatus.ADDED
    return FileAddStatus.PRESENT
  }

  override fun addFileId(fileId: Int, project: Project): FileAddStatus {
    return myProjectFilters[project]?.ensureFileIdPresent(fileId) { true } ?: FileAddStatus.SKIPPED
  }

  override fun removeFile(fileId: Int) {
    for (filter in myProjectFilters.values) {
      filter.removeFileId(fileId)
    }
  }

  override fun findProjectForFile(fileId: Int): Project? {
    for ((project, filter) in myProjectFilters) {
      if (filter.containsFileId(fileId)) {
        return project
      }
    }
    return null
  }

  override fun runHealthCheck() {
    try {
      for ((project, filter) in myProjectFilters) {
        var errors: List<HealthCheckError>? = null
        ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
          if (DumbService.isDumb(project)) return@runInReadActionWithWriteActionPriority
          errors = runHealthCheck(project, filter)
        }, ProgressManager.getInstance().progressIndicator)

        if (errors.isNullOrEmpty()) continue

        for (error in errors!!) {
          error.fix(filter)
        }

        val message = StringUtil.first(errors!!.map { ReadAction.nonBlocking(Callable { it.presentableText }) }.joinToString(", "),
                                       300,
                                       true)
        CoreFileBasedIndex.LOG.error("Project indexable filter health check errors: $message")

      }
    }
    catch (_: ProcessCanceledException) {

    }
    catch (e: Exception) {
      CoreFileBasedIndex.LOG.error(e)
    }
  }

  private fun runHealthCheck(project: Project, filter: IncrementalProjectIndexableFilesFilter): List<HealthCheckError> {
    val errors = mutableListOf<HealthCheckError>()
    val index = FileBasedIndex.getInstance() as CoreFileBasedIndex
    index.iterateIndexableFiles(ContentIterator {
      if (it is VirtualFileWithId) {
        val fileId = it.id
        if (!filter.containsFileId(fileId)) {
          filter.ensureFileIdPresent(fileId) { true }
        }
      }
      true
    }, project, ProgressManager.getInstance().progressIndicator)
    return errors
  }

  private class HealthCheckError(private val project: Project, private val virtualFile: VirtualFile) {
    val presentableText: String
      get() = "file ${virtualFile.path} not found in ${project.name}"

    fun fix(filter: IncrementalProjectIndexableFilesFilter) {
      filter.ensureFileIdPresent((virtualFile as VirtualFileWithId).id) { true }
    }

  }
}