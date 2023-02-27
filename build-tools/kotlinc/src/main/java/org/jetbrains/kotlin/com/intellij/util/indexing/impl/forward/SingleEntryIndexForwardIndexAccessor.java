package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.VolatileNotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexId;
import org.jetbrains.kotlin.com.intellij.util.indexing.SingleEntryFileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.DirectInputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.KeyValueUpdateProcessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.RemovedKeyProcessor;
import org.jetbrains.kotlin.com.intellij.util.io.VoidDataExternalizer;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class SingleEntryIndexForwardIndexAccessor<V> extends AbstractMapForwardIndexAccessor<Integer, V, Void> {
  private static final Logger LOG = Logger.getInstance(SingleEntryIndexForwardIndexAccessor.class);
  private final NotNullLazyValue<UpdatableIndex<Integer, V, ?, ?>> myIndex;

  @SuppressWarnings("unchecked")
  public SingleEntryIndexForwardIndexAccessor(IndexExtension<Integer, V, ?> extension) {
    super(VoidDataExternalizer.INSTANCE);
    LOG.assertTrue(extension instanceof SingleEntryFileBasedIndexExtension);
    IndexId<?, ?> name = extension.getName();
    FileBasedIndexEx fileBasedIndex = (FileBasedIndexEx) FileBasedIndex.getInstance();
    myIndex = new VolatileNotNullLazyValue<UpdatableIndex<Integer, V, ?, ?>>() {
      @Override
      protected @NotNull UpdatableIndex<Integer, V, ?, ?> compute() {
        return fileBasedIndex.getIndex(
                (ID<Integer, V>) name);
      }
    };
  }

  @NotNull
  @Override
  public final InputDataDiffBuilder<Integer, V> getDiffBuilder(int inputId, @Nullable ByteArraySequence sequence) throws IOException {
    Map<Integer, V> data;
    try {
      data = ProgressManager.getInstance().computeInNonCancelableSection(() -> myIndex.getValue().getIndexedFileData(inputId));
    }
    catch (StorageException e) {
      throw new IOException(e);
    }
    return createDiffBuilderByMap(inputId, data);
  }

  @Nullable
  @Override
  public Void convertToDataType(@NotNull InputData<Integer, V> data) {
    return null;
  }


  @Override
  public @NotNull InputDataDiffBuilder<Integer, V> createDiffBuilderByMap(int inputId, @Nullable Map<Integer, V> map) throws IOException {
    if (map == null) {
      map = Collections.emptyMap();
    }
    return new SingleValueDiffBuilder<>(inputId, map);
  }

  @Nullable
  @Override
  public final ByteArraySequence serializeIndexedData(@NotNull InputData<Integer, V> data) {
    return null;
  }

  @Override
  protected @Nullable Map<Integer, V> convertToMap(int inputId, @Nullable Void inputData) throws IOException {
    try {
      return ProgressManager.getInstance().computeInNonCancelableSection(() -> myIndex.getValue().getIndexedFileData(inputId));
    }
    catch (StorageException e) {
      throw new IOException(e);
    }
  }

  public static class SingleValueDiffBuilder<V> extends DirectInputDataDiffBuilder<Integer, V> {
    private final boolean myContainsValue;
    @Nullable
    private final V myCurrentValue;

    public SingleValueDiffBuilder(int inputId, @NotNull Map<Integer, V> currentData) {
      this(inputId, !currentData.isEmpty(), ContainerUtil.getFirstItem(currentData.values()));
    }

    private SingleValueDiffBuilder(int inputId, boolean containsValue, @Nullable V currentValue) {
      super(inputId);
      myContainsValue = containsValue;
      myCurrentValue = currentValue;
    }

    @Override
    public @NotNull Collection<Integer> getKeys() {
      return myContainsValue ? Collections.singleton(myInputId) : Collections.emptySet();
    }

    @Override
    public boolean differentiate(@NotNull Map<Integer, V> newData,
                                 @NotNull KeyValueUpdateProcessor<? super Integer, ? super V> addProcessor,
                                 @NotNull KeyValueUpdateProcessor<? super Integer, ? super V> updateProcessor,
                                 @NotNull RemovedKeyProcessor<? super Integer> removeProcessor) throws StorageException {
      boolean newValueExists = !newData.isEmpty();
      V newValue = ContainerUtil.getFirstItem(newData.values());
      if (myContainsValue) {
        if (!newValueExists) {
          removeProcessor.process(myInputId, myInputId);
          return true;
        } else if (Comparing.equal(myCurrentValue, newValue)) {
          return false;
        } else {
          updateProcessor.process(myInputId, newValue, myInputId);
          return true;
        }
      } else {
        if (newValueExists) {
          addProcessor.process(myInputId, newValue, myInputId);
          return true;
        } else {
          return false;
        }
      }
    }
  }
}