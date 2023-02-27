package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;

import java.util.Collections;
import java.util.Map;

/**
 * Simplifies API and ensures that data key will always be equal to virtual file id
 *
 * @author Eugene Zhuravlev
 */
//@ApiStatus.OverrideOnly
public abstract class SingleEntryIndexer<V> implements DataIndexer<Integer, V, FileContent>{
  private final boolean myAcceptNullValues;

  protected SingleEntryIndexer(boolean acceptNullValues) {
    myAcceptNullValues = acceptNullValues;
  }

  @Override
  @NonNull
  public final Map<Integer, V> map(@NonNull FileContent inputData) {
    final V value = computeValue(inputData);
    if (value == null && !myAcceptNullValues) {
      return Collections.emptyMap();
    }
    VirtualFile file = inputData.getFile();
    int key = file instanceof LightVirtualFile ? -1 : ((VirtualFileWithId) file).getId();
    return Collections.singletonMap(key, value);
  }

  protected abstract @Nullable V computeValue(@NonNull FileContent inputData);

  @ApiStatus.Internal
  public boolean isAcceptNullValues() {
    return myAcceptNullValues;
  }
}