package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

class ProjectChangedFilesScanner {
  private static final Logger LOG = Logger.getInstance(ProjectChangedFilesScanner.class);
  private @NotNull final Project myProject;

  ProjectChangedFilesScanner(@NotNull Project project) {
    myProject = project;
  }

  public Collection<VirtualFile> scan(String fileSetName) {
    long refreshedFilesCalcDuration = System.nanoTime();
    Collection<VirtualFile> files = Collections.emptyList();
    try {
      CoreFileBasedIndex fileBasedIndex = (CoreFileBasedIndex) FileBasedIndex.getInstance();
      files = fileBasedIndex.getFilesToUpdate(myProject);
      return files;
    }
    finally {
      refreshedFilesCalcDuration = System.nanoTime() - refreshedFilesCalcDuration;
//      ScanningStatistics scanningStatistics = new ScanningStatistics(fileSetName);
//      scanningStatistics.setNumberOfScannedFiles(files.size());
//      scanningStatistics.setNumberOfFilesForIndexing(files.size());
//      scanningStatistics.setScanningTime(refreshedFilesCalcDuration);
//      scanningStatistics.setNoRootsForRefresh();
//      projectIndexingHistory.addScanningStatistics(scanningStatistics);
//      projectIndexingHistory.setScanFilesDuration(Duration.ofNanos(refreshedFilesCalcDuration));

      LOG.info("Scanning refreshed files of " + myProject.getName() + " : " + files.size() + " to update, " +
               "calculated in " + TimeUnit.NANOSECONDS.toMillis(refreshedFilesCalcDuration) + "ms");
    }
  }
}