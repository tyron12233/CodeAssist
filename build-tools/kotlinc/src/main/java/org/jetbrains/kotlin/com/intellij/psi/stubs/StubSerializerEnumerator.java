package org.jetbrains.kotlin.com.intellij.psi.stubs;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.kotlin.com.intellij.concurrency.ConcurrentCollectionFactory;
import org.jetbrains.kotlin.com.intellij.openapi.Forceable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectHashMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectMap;
import org.jetbrains.kotlin.com.intellij.util.io.DataEnumeratorEx;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentStringEnumerator;
import org.jetbrains.kotlin.com.intellij.util.io.SelfDiagnosing;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

final class StubSerializerEnumerator implements Flushable, Closeable {
  private static final Logger LOG = Logger.getInstance(StubSerializerEnumerator.class);

  private final DataEnumeratorEx<String> myNameStorage;

  private final Int2ObjectMap<String> myIdToName = new Int2ObjectOpenHashMap<>();
  private final Object2IntMap<String> myNameToId = new Object2IntOpenHashMap<>();
  private final Map<String, Supplier<? extends ObjectStubSerializer<?, ? extends Stub>>> myNameToLazySerializer = CollectionFactory.createSmallMemoryFootprintMap();

  private final ConcurrentIntObjectMap<ObjectStubSerializer<?, ? extends Stub>> myIdToSerializer =
    ConcurrentCollectionFactory.createConcurrentIntObjectMap();
  private final Map<ObjectStubSerializer<?, ? extends Stub>, Integer> mySerializerToId = new ConcurrentHashMap<>();

  private final boolean myUnmodifiable;

  StubSerializerEnumerator(@NonNull DataEnumeratorEx<String> nameStorage, boolean unmodifiable) {
    myNameStorage = nameStorage;
    myUnmodifiable = unmodifiable;
  }

  void dropRegisteredSerializers() {
    myIdToName.clear();
    myNameToId.clear();
    myNameToLazySerializer.clear();

    Enumeration<ObjectStubSerializer<?, ? extends Stub>> elements = myIdToSerializer.elements();
    while (elements.hasMoreElements()) {
      ObjectStubSerializer<?, ? extends Stub> element = elements.nextElement();
      myIdToSerializer.remove(getClassId(element));
    }
    mySerializerToId.clear();
  }

  @NonNull ObjectStubSerializer<?, Stub> getClassById(@NonNull MissingSerializerReporter reporter, int id) throws SerializerNotFoundException {
    ObjectStubSerializer<?, ? extends Stub> serializer = myIdToSerializer.get(id);
    if (serializer == null) {
      serializer = instantiateSerializer(id, reporter);
      myIdToSerializer.put(id, serializer);
    }
    //noinspection unchecked
    return (ObjectStubSerializer<?, Stub>)serializer;
  }

  int getClassId(final @NonNull ObjectStubSerializer<?, ? extends Stub> serializer) {
    Integer idValue = mySerializerToId.get(serializer);
    if (idValue == null) {
      String name = serializer.getExternalId();
      idValue = myNameToId.getInt(name);
      assert idValue > 0 : "No ID found for serializer " + serializer + ", external id:" + name +
                           (serializer instanceof IElementType ? ", language:" + ((IElementType)serializer).getLanguage() : "");
      mySerializerToId.put(serializer, idValue);
    }
    return idValue;
  }

  void assignId(@NonNull Supplier<? extends ObjectStubSerializer<?, ? extends Stub>> serializer, String name) throws IOException {
    Supplier<? extends ObjectStubSerializer<?, ? extends Stub>> old = myNameToLazySerializer.put(name, serializer);
    if (old != null && !isKnownDuplicatedIdViolation(name)) {
      ObjectStubSerializer<?, ? extends Stub> existing = old.get();
      ObjectStubSerializer<?, ? extends Stub> computed = serializer.get();
      if (existing != computed) {
        throw new AssertionError("ID: " + name + " is not unique, but found in both " +
                                 existing.getClass().getName() + " and " + computed.getClass().getName());
      }
      return;
    }

    int id;
    if (myUnmodifiable) {
      id = myNameStorage.tryEnumerate(name);
      if (id == 0) {
        LOG.debug("serialized " + name + " is ignored in unmodifiable stub serialization manager");
        return;
      }
    }
    else {
      id = myNameStorage.enumerate(name);
    }
    myIdToName.put(id, name);
    myNameToId.put(name, id);
  }

  private static boolean isKnownDuplicatedIdViolation(String name) {
    // // todo temporary https://github.com/JetBrains/kotlin/commit/298494fa08d11b9c374368aac4ae547b6f972f1a
    return "kotlin.FILE".equals(name);
  }

  @Nullable
  String getSerializerName(int id) {
    return myIdToName.get(id);
  }

  int getSerializerId(@NonNull String name) {
    return myNameToId.getInt(name);
  }

  @NonNull
  ObjectStubSerializer<?, ? extends Stub> getSerializer(@NonNull String name) throws SerializerNotFoundException {
    int id = myNameToId.getInt(name);
    return getClassById((id1, name1, externalId) -> {
      return "Missed stub serializer for " + name;
    }, id);
  }

  @Nullable
  String getSerializerName(@NonNull ObjectStubSerializer<?, ? extends Stub> serializer) {
    return myIdToName.get(getClassId(serializer));
  }

  private @NonNull ObjectStubSerializer<?, ? extends Stub> instantiateSerializer(int id,
                                                                                 @NonNull MissingSerializerReporter reporter) throws SerializerNotFoundException {
    String name = myIdToName.get(id);
    Supplier<? extends ObjectStubSerializer<?, ? extends Stub>> lazy = name == null ? null : myNameToLazySerializer.get(name);
    ObjectStubSerializer<?, ? extends Stub> serializer = lazy == null ? null : lazy.get();
    if (serializer == null) {
      throw reportMissingSerializer(id, name, reporter);
    }
    return serializer;
  }

  private SerializerNotFoundException reportMissingSerializer(int id, @Nullable String name, @NonNull MissingSerializerReporter reporter) {
    String externalId = null;
    Throwable storageException = null;
    try {
      externalId = myNameStorage.valueOf(id);
    } catch (Throwable e) {
      LOG.info(e);
      storageException = e;
    }
    SerializerNotFoundException exception = new SerializerNotFoundException(reporter.report(id, name, externalId));
    StubIndex.getInstance().forceRebuild(storageException != null ? storageException : exception);
    return exception;
  }

  @Override
  public void flush() throws IOException {
    if (myNameStorage instanceof Forceable) {
//      if (((Forceable?)myNameStorage).isDirty()) {
        ((Forceable)myNameStorage).force();
//      }
    }
  }

  @Override
  public void close() throws IOException {
    if (myNameStorage instanceof Closeable) {
      ((Closeable)myNameStorage).close();
    }
  }

  @ApiStatus.Internal
  Map<String, Integer> dump() {
    assert myUnmodifiable;
    assert myNameStorage instanceof PersistentStringEnumerator;
    try {
      Collection<String> stubNames = ((PersistentStringEnumerator)myNameStorage).getAllDataObjects(null);
      Map<String, Integer> dump = new HashMap<>();
      for (String name : stubNames) {
        dump.put(name, myNameStorage.tryEnumerate(name));
      }
      return dump;
    }
    catch (IOException e) {
      LOG.error(e);
    }
    return Collections.emptyMap();
  }

  public void tryDiagnose() {
    if (myNameStorage instanceof SelfDiagnosing) {
      ((SelfDiagnosing)myNameStorage).diagnose();
    }
  }

  @FunctionalInterface
  interface MissingSerializerReporter {
    @NonNull
    String report(int id, @Nullable String name, @Nullable String externalId);
  }
}