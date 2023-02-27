package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.SingleEntryIndexForwardIndexAccessor;

import java.io.IOException;
import java.util.Map;

class StubUpdatingForwardIndexAccessor extends SingleEntryIndexForwardIndexAccessor<SerializedStubTree> {
  StubUpdatingForwardIndexAccessor(@NotNull FileBasedIndexExtension<Integer, SerializedStubTree> extension) {
    super(extension);
  }

  @Override
  public @NotNull InputDataDiffBuilder<Integer, SerializedStubTree> createDiffBuilderByMap(int inputId,
                                                                                           @Nullable Map<Integer, SerializedStubTree> data)
    throws IOException {
    SerializedStubTree tree = data == null || data.isEmpty() ? null : ContainerUtil.getFirstItem(data.values());
    if (tree != null) {
      tree.restoreIndexedStubs();
    }
    return new StubCumulativeInputDiffBuilder(inputId, tree);
  }
}