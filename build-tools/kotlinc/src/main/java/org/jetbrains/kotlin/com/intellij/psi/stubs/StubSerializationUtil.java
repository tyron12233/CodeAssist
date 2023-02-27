package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

final class StubSerializationUtil {
  private StubSerializationUtil() {}

  static ObjectStubSerializer<Stub, Stub> getSerializer(@NonNull Stub rootStub) {
    if (rootStub instanceof PsiFileStub) {
      //noinspection unchecked
      return ((PsiFileStub<?>)rootStub).getType();
    }
    //noinspection unchecked
    return (ObjectStubSerializer<Stub, Stub>)rootStub.getStubType();
  }

  /**
   * Format warning for {@link ObjectStubSerializer} not being able to deserialize given stub.
   *
   * @param root - serializer which couldn't deserialize stub
   * @return message for broken stub format
   */
  static @NonNull String brokenStubFormat(@NonNull ObjectStubSerializer<?, ?> root) {
    return "Broken stub format, most likely version of " + root + " (" + root.getExternalId() + ") was not updated after serialization changes\n";
  }
}