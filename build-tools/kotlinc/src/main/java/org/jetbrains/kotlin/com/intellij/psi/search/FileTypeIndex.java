package org.jetbrains.kotlin.com.intellij.psi.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.CommonProcessors;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.messages.Topic;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Dmitry Avdeev
 */
public final class FileTypeIndex {
  /**
   * @deprecated please don't use this index directly.
   *
   * Use {@link #getFiles(FileType, GlobalSearchScope)},
   * {@link #containsFileOfType(FileType, GlobalSearchScope)} or
   * {@link #processFiles(FileType, Processor, GlobalSearchScope)} instead
   */
  @Deprecated
  public static final ID<FileType, Void> NAME = ID.create("filetypes");

  @Nullable
  public static FileType getIndexedFileType(@NonNull VirtualFile file, @NonNull Project project) {
    Map<FileType, Void> data = FileBasedIndex.getInstance().getFileData(NAME, file, project);
    return ContainerUtil.getFirstItem(data.keySet());
  }

  @NonNull
  public static Collection<VirtualFile> getFiles(@NonNull FileType fileType, @NonNull GlobalSearchScope scope) {
    return FileBasedIndex.getInstance().getContainingFiles(NAME, fileType, scope);
  }

  public static boolean containsFileOfType(@NonNull FileType type, @NonNull GlobalSearchScope scope) {
    return !processFiles(type, virtualFile -> false, scope);
  }

  public static boolean processFiles(@NonNull FileType fileType, @NonNull Processor<? super VirtualFile> processor, @NonNull GlobalSearchScope scope) {
    @NonNull Iterator<VirtualFile> files = FileBasedIndex.getInstance().getContainingFilesIterator(NAME, fileType, scope);
    while (files.hasNext()) {
      VirtualFile file = files.next();
      if (!processor.process(file)) {
        return false;
      }
    }
    return true;
  }

//  @ApiStatus.Experimental
  public static final Topic<IndexChangeListener> INDEX_CHANGE_TOPIC =
    new Topic<>(IndexChangeListener.class, Topic.BroadcastDirection.TO_DIRECT_CHILDREN);
//  @ApiStatus.Experimental
  public interface IndexChangeListener {
    /**
     * This event means that the set of files corresponding to the {@code fileType} has changed
     * (i.e. gets fired for both additions and removals).
     */
    void onChangedForFileType(@NonNull FileType fileType);
  }
}