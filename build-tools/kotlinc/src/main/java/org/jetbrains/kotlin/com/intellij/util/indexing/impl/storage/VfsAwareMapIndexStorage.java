package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.VfsAwareIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

public class VfsAwareMapIndexStorage<Key, Value> extends MapIndexStorage<Key, Value> implements VfsAwareIndexStorage<Key, Value> {
  private final boolean myBuildKeyHashToVirtualFileMapping;
  @Nullable
  private KeyHashLog<Key> myKeyHashToVirtualFileMapping;

  @TestOnly
  public VfsAwareMapIndexStorage(Path storageFile,
                                 @NotNull KeyDescriptor<Key> keyDescriptor,
                                 @NotNull DataExternalizer<Value> valueExternalizer,
                                 final int cacheSize,
                                 final boolean readOnly
  ) throws IOException {
    super(storageFile, keyDescriptor, valueExternalizer, cacheSize, false, true, readOnly, false, null);
    myBuildKeyHashToVirtualFileMapping = false;
  }

  public VfsAwareMapIndexStorage(Path storageFile,
                                 @NotNull KeyDescriptor<Key> keyDescriptor,
                                 @NotNull DataExternalizer<Value> valueExternalizer,
                                 final int cacheSize,
                                 boolean keyIsUniqueForIndexedFile,
                                 boolean buildKeyHashToVirtualFileMapping,
                                 boolean enableWal) throws IOException {
    super(storageFile,
          keyDescriptor,
          valueExternalizer,
          cacheSize,
          keyIsUniqueForIndexedFile,
          false,
          false,
          enableWal,
          null);
    myBuildKeyHashToVirtualFileMapping = buildKeyHashToVirtualFileMapping;
    initMapAndCache();
  }

  @Override
  protected void initMapAndCache() throws IOException {
    super.initMapAndCache();
    if (myBuildKeyHashToVirtualFileMapping && myBaseStorageFile != null) {
      FileSystem projectFileFS = myBaseStorageFile.getFileSystem();
      assert !projectFileFS.isReadOnly() : "File system " + projectFileFS + " is read only";
      myKeyHashToVirtualFileMapping = new KeyHashLog<>(myKeyDescriptor, myBaseStorageFile);
    }
    else {
      myKeyHashToVirtualFileMapping = null;
    }
  }

  @Override
  public void flush() throws IOException {
    super.flush();
      if (myKeyHashToVirtualFileMapping != null) {
          myKeyHashToVirtualFileMapping.force();
      }
  }

  @Override
  public void close() throws StorageException {
    super.close();
    try {
        if (myKeyHashToVirtualFileMapping != null) {
            myKeyHashToVirtualFileMapping.close();
        }
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    catch (RuntimeException e) {
      unwrapCauseAndRethrow(e);
    }
  }

  @Override
  public void clear() throws StorageException{
    try {
        if (myKeyHashToVirtualFileMapping != null) {
            myKeyHashToVirtualFileMapping.close();
        }
    }
    catch (Exception ignored) { }
    super.clear();
  }

  @Override
  public boolean processKeys(@NotNull Processor<? super Key> processor, GlobalSearchScope scope, @Nullable IdFilter idFilter) throws StorageException {
    try {
      clearCachedMappings();

      Project project = scope.getProject();
      if (myKeyHashToVirtualFileMapping != null && project != null && idFilter != null) {
        IntSet hashMaskSet = myKeyHashToVirtualFileMapping.getSuitableKeyHashes(idFilter, project);
        if (hashMaskSet != null) {
          return doProcessKeys(key -> {
              if (!hashMaskSet.contains(myKeyDescriptor.getHashCode(key))) {
                  return true;
              }
            return processor.process(key);
          });
        }
      }
      return doProcessKeys(processor);
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    catch (RuntimeException e) {
      return unwrapCauseAndRethrow(e);
    }
  }

  @Override
  public void removeAllValues(@NotNull Key key, int inputId) throws StorageException {
    if (myKeyHashToVirtualFileMapping != null) {
      myKeyHashToVirtualFileMapping.removeKeyHashToVirtualFileMapping(key, inputId);
    }
    super.removeAllValues(key, inputId);
  }

  @Override
  public void addValue(final Key key, final int inputId, final Value value) throws StorageException {
    if (myKeyHashToVirtualFileMapping != null) {
      myKeyHashToVirtualFileMapping.addKeyHashToVirtualFileMapping(key, inputId);
    }
    super.addValue(key, inputId, value);
  }
}