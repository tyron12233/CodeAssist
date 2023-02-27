package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.MapReduceIndexBase;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;

import java.io.IOException;

@ApiStatus.Internal
public abstract class StubUpdatableIndexFactory {
  static StubUpdatableIndexFactory getInstance() {
    return ApplicationManager.getApplication().getService(StubUpdatableIndexFactory.class);
  }

  @NotNull
  public abstract MapReduceIndexBase<Integer, SerializedStubTree, ?> createIndex(@NotNull FileBasedIndexExtension<Integer, SerializedStubTree> extension,
                                                                                 @NotNull VfsAwareIndexStorageLayout<Integer, SerializedStubTree> layout,
                                                                                 @NotNull SerializationManagerEx serializationManager)
    throws IOException;
}