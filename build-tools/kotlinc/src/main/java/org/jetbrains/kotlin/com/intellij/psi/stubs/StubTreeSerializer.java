package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import java.io.InputStream;
import java.io.OutputStream;

public interface StubTreeSerializer {
  void serialize(@NonNull Stub rootStub, @NonNull OutputStream stream);

  @NonNull Stub deserialize(@NonNull InputStream stream) throws SerializerNotFoundException;
}