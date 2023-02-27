package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

final class ProjectFilesCondition implements Condition<VirtualFile> {
  private static final int MAX_FILES_TO_UPDATE_FROM_OTHER_PROJECT = 2;
  private final VirtualFile myRestrictedTo;
  private final GlobalSearchScope myFilter;
  private int myFilesFromOtherProjects;
  private final IdFilter myIndexableFilesFilter;

  ProjectFilesCondition(IdFilter indexableFilesFilter,
                        GlobalSearchScope filter,
                        VirtualFile restrictedTo,
                        boolean includeFilesFromOtherProjects) {
    myRestrictedTo = restrictedTo;
    myFilter = filter;
    myIndexableFilesFilter = indexableFilesFilter;
    if (!includeFilesFromOtherProjects) {
      myFilesFromOtherProjects = MAX_FILES_TO_UPDATE_FROM_OTHER_PROJECT;
    }
  }

  @Override
  public boolean value(VirtualFile file) {
    int fileId = ((VirtualFileWithId)file).getId();
    if (myIndexableFilesFilter != null
//        && !(file instanceof DeletedVirtualFileStub)
        &&
        !myIndexableFilesFilter.containsFileId(fileId)) {
        if (myFilesFromOtherProjects >= MAX_FILES_TO_UPDATE_FROM_OTHER_PROJECT) {
            return false;
        }
      ++myFilesFromOtherProjects;
      return true;
    }

//    if (file instanceof DeletedVirtualFileStub) {
//      return true;
//    }
      if (FileBasedIndexEx.belongsToScope(file, myRestrictedTo, myFilter)) {
          return true;
      }

    if (myFilesFromOtherProjects < MAX_FILES_TO_UPDATE_FROM_OTHER_PROJECT) {
      ++myFilesFromOtherProjects;
      return true;
    }
    return false;
  }
}