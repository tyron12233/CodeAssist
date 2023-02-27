package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This class is intended to manage Stub Serializers {@link ObjectStubSerializer} and stub serialization/deserialization algorithm.
 */
//@ApiStatus.Internal
public abstract class SerializationManagerEx implements StubTreeSerializer {
  public static SerializationManagerEx getInstanceEx() {
    return ApplicationManager.getApplication().getService(SerializationManagerEx.class);
  }

  public abstract void performShutdown();

  /**
   * @deprecated only kept to support prebuilt stubs
   */
  @Deprecated
  public abstract void reSerialize(@NonNull InputStream inStub,
                                   @NonNull OutputStream outStub,
                                   @NonNull StubTreeSerializer newSerializationManager) throws IOException;

  protected abstract void initSerializers();

  public abstract void initialize();

  public abstract boolean isNameStorageCorrupted();

  public abstract void repairNameStorage(@NonNull Exception corruptionCause);

  /**
   * @deprecated use {@link SerializationManagerEx#repairNameStorage(Exception)}
   * with specified corruption cause
   */
  @Deprecated(forRemoval = true)
  public void repairNameStorage() {
    repairNameStorage(new Exception());
  }

  public abstract void flushNameStorage() throws IOException;

  public abstract void reinitializeNameStorage();
}