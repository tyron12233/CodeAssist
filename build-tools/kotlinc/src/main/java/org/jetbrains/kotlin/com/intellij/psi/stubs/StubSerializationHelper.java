package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.io.StreamUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class StubSerializationHelper extends StubTreeSerializerBase<IntEnumerator> {
  @NonNull
  private final StubSerializerEnumerator myEnumerator;

  StubSerializationHelper(@NonNull StubSerializerEnumerator enumerator) {
    myEnumerator = enumerator;
  }

  @NonNull
  @Override
  protected IntEnumerator readSerializationState(@NonNull StubInputStream stream) throws IOException {
    return IntEnumerator.read(stream);
  }

  @NonNull
  @Override
  protected IntEnumerator createSerializationState() {
    return new IntEnumerator();
  }

  @Override
  protected void saveSerializationState(@NonNull IntEnumerator enumerator, @NonNull DataOutputStream stream) throws IOException {
    enumerator.dump(stream);
  }

  @Override
  protected int writeSerializerId(@NonNull ObjectStubSerializer<Stub, Stub> serializer,
                                  @NonNull IntEnumerator enumerator) throws IOException {
    return enumerator.enumerate(myEnumerator.getClassId(serializer));
  }

  @Override
  protected ObjectStubSerializer<?, Stub> getClassByIdLocal(int localId,
                                                            @Nullable Stub parentStub,
                                                            @NonNull IntEnumerator enumerator) throws SerializerNotFoundException {
    int id = enumerator.valueOf(localId);
    return myEnumerator.getClassById((id1, name, externalId) -> {
      myEnumerator.tryDiagnose();
      ObjectStubSerializer<?, ? extends Stub> root = ourRootStubSerializer.get();
      return (root != null ? StubSerializationUtil.brokenStubFormat(root) : "") +
             "No serializer is registered for stub ID: " +
             id1 + ", externalId: " + externalId + ", name: " + name +
             "; parent stub class: " + (parentStub != null ? parentStub.getClass().getName() + ", parent stub type: " + parentStub.getStubType() : "null");
    }, id);
  }

  void reSerializeStub(@NonNull DataInputStream inStub,
                       @NonNull DataOutputStream outStub,
                       @NonNull StubSerializationHelper newSerializationHelper) throws IOException {
    IntEnumerator currentSerializerEnumerator = IntEnumerator.read(inStub);
    currentSerializerEnumerator.dump(outStub, id -> {
      String name = myEnumerator.getSerializerName(id);
      return name == null ? 0 : newSerializationHelper.myEnumerator.getSerializerId(name);
    });
    StreamUtil.copy(inStub, outStub);
  }
}