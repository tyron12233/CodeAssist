package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexId;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

import java.io.IOException;
import java.util.Map;

public final class UpdateData<Key, Value> extends AbstractUpdateData<Key, Value> {
  private final Map<Key, Value> myNewData;
  private final @NonNull ThrowableComputable<? extends InputDataDiffBuilder<Key, Value>, IOException>
          myCurrentDataEvaluator;
  private final IndexId<Key, Value> myIndexId;
  private final ThrowableRunnable<? extends IOException> myForwardIndexUpdate;

  public UpdateData(int inputId,
                    @NonNull Map<Key, Value> newData,
                    @NonNull ThrowableComputable<? extends InputDataDiffBuilder<Key, Value>, IOException> currentDataEvaluator,
                    @NonNull IndexId<Key, Value> indexId,
                    @Nullable ThrowableRunnable<? extends IOException> forwardIndexUpdate) {
    super(inputId);
    myNewData = newData;
    myCurrentDataEvaluator = currentDataEvaluator;
    myIndexId = indexId;
    myForwardIndexUpdate = forwardIndexUpdate;
  }

  @Override
  protected boolean iterateKeys(@NonNull KeyValueUpdateProcessor<? super Key, ? super Value> addProcessor,
                                @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> updateProcessor,
                                @NonNull RemovedKeyProcessor<? super Key> removeProcessor) throws StorageException {
    final InputDataDiffBuilder<Key, Value> currentData;
    try {
      currentData = myCurrentDataEvaluator.compute();
    }
    catch (IOException e) {
      throw new StorageException("Error while applying " + this, e);
    }
    return currentData.differentiate(myNewData, addProcessor, updateProcessor, removeProcessor);
  }

  @Override
  protected void updateForwardIndex() throws IOException {
    if (myForwardIndexUpdate != null) {
      myForwardIndexUpdate.run();
    }
  }

  @Override
  public String toString() {
    return "update data for " + getInputId() + " of " + myIndexId;
  }
}