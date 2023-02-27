package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.MapReduceIndexBase;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;

import java.io.IOException;

public class StubUpdatableIndexFactoryImpl extends StubUpdatableIndexFactory {

    @Override
    public @NotNull MapReduceIndexBase<Integer, SerializedStubTree, ?> createIndex(@NotNull FileBasedIndexExtension<Integer, SerializedStubTree> extension,
                                                                                   @NotNull VfsAwareIndexStorageLayout<Integer, SerializedStubTree> layout,
                                                                                   @NotNull SerializationManagerEx serializationManager) throws IOException {
        return new StubUpdatingIndexStorage(extension, layout, serializationManager);
    }
}
