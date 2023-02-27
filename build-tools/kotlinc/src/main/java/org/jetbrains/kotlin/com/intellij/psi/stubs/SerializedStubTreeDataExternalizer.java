package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class SerializedStubTreeDataExternalizer implements DataExternalizer<SerializedStubTree> {
  @NotNull
  private final StubTreeSerializer mySerializationManager;
  private final StubForwardIndexExternalizer<?> myStubIndexesExternalizer;

  public SerializedStubTreeDataExternalizer(@NotNull StubTreeSerializer manager, @NotNull StubForwardIndexExternalizer<?> externalizer) {
    mySerializationManager = manager;
    myStubIndexesExternalizer = externalizer;
  }

  @Override
  public final void save(@NotNull final DataOutput out, @NotNull final SerializedStubTree tree) throws IOException {
    DataInputOutputUtil.writeINT(out, tree.myTreeByteLength);
    out.write(tree.myTreeBytes, 0, tree.myTreeByteLength);
    DataInputOutputUtil.writeINT(out, tree.myIndexedStubByteLength);
    out.write(tree.myIndexedStubBytes, 0, tree.myIndexedStubByteLength);
  }

  @NotNull
  @Override
  public final SerializedStubTree read(@NotNull final DataInput in) throws IOException {
    int serializedStubsLength = DataInputOutputUtil.readINT(in);
    byte[] bytes = new byte[serializedStubsLength];
    in.readFully(bytes);
    int indexedStubByteLength;
    byte[] indexedStubBytes;
    indexedStubByteLength = DataInputOutputUtil.readINT(in);
    indexedStubBytes = new byte[indexedStubByteLength];
    in.readFully(indexedStubBytes);
    return new SerializedStubTree(bytes, bytes.length, indexedStubBytes, indexedStubByteLength,
                                  null, myStubIndexesExternalizer, mySerializationManager);
  }


}