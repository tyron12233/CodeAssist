package org.jetbrains.kotlin.com.intellij.psi.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.CustomImplementationFileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.DataIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.ScalarIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;

import java.io.IOException;
import java.util.Collections;

public final class FileTypeIndexImpl
  extends ScalarIndexExtension<FileType>
  implements CustomImplementationFileBasedIndexExtension<FileType, Void> {
  private static final boolean USE_LOG_INDEX = SystemProperties.getBooleanProperty("use.log.file.type.index", false);
  private static final boolean USE_MAPPED_INDEX = SystemProperties.getBooleanProperty("use.mapped.file.type.index", true);

  @NotNull
  @Override
  public ID<FileType, Void> getName() {
    return FileTypeIndex.NAME;
  }

  @NotNull
  @Override
  public DataIndexer<FileType, Void, FileContent> getIndexer() {
    if (USE_LOG_INDEX || USE_MAPPED_INDEX) {
      throw new UnsupportedOperationException();
    }
    return in -> Collections.singletonMap(in.getFileType(), null);
  }

  @NotNull
  @Override
  public KeyDescriptor<FileType> getKeyDescriptor() {
    return new FileTypeKeyDescriptor();
  }

  @NotNull
  @Override
  public FileBasedIndex.InputFilter getInputFilter() {
    return file -> !file.isDirectory();
  }

  @Override
  public boolean dependsOnFileContent() {
    return false;
  }

  @Override
  public int getVersion() {
    return USE_MAPPED_INDEX ? 0x1000 : 3 + (USE_LOG_INDEX ? 0xFF : 0);
  }

  @Override
  public @NotNull UpdatableIndex<FileType, Void, FileContent, ?> createIndexImplementation(@NotNull FileBasedIndexExtension<FileType, Void> extension,
                                                                                           @NotNull VfsAwareIndexStorageLayout<FileType, Void> indexStorageLayout)
    throws StorageException, IOException {
    return new MappedFileTypeIndex(extension);
//    return USE_MAPPED_INDEX ? new MappedFileTypeIndex(extension) :
//           USE_LOG_INDEX ? new LogFileTypeIndex(extension) :
//           new FileTypeMapReduceIndex(extension, indexStorageLayout);
  }
}