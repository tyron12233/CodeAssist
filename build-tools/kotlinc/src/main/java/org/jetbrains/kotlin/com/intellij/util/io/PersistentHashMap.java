package org.jetbrains.kotlin.com.intellij.util.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.jetbrains.kotlin.com.intellij.util.io.PersistentMapBuilder.newBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.CommonProcessors;
import org.jetbrains.kotlin.com.intellij.util.Processor;

/**
 * A delegate for a Persistent Hash Map (PHM) implementation
 * <p/>
 * This class plays several roles to preserve backward API compatibility:
 * <ul>
 *   <li>base interface for {@link PersistentHashMap}, so please use that one in any public or open API</li>
 *   <li>it delegates all calls to {@link PersistentMapBase} implementation</li>
 *   <li>factory adapter for backward compatibility - constructors delegates to {@link PersistentMapBuilder} to create the best implementation</li>
 * </ul>
 *
 * @see PersistentMapBuilder
 **/
public final class PersistentHashMap<Key, Value> implements AppendablePersistentMap<Key, Value>, MeasurableIndexStore {

  static final String DATA_FILE_EXTENSION = ".values";

  @NonNull
  private final PersistentMapBase<Key, Value> myImpl;

  PersistentHashMap(@NonNull PersistentMapBuilder<Key, Value> builder, boolean checkInheritedMembers) throws IOException {
    if (checkInheritedMembers) {
      builder.withReadonly(false);
      builder.inlineValues(false);
    }
    //noinspection resource
    myImpl = builder.build().myImpl;
  }

  public PersistentHashMap(@NonNull PersistentMapBase<Key, Value> impl) {
    myImpl = impl;
  }

  @Override
  public void closeAndClean() throws IOException {
    myImpl.closeAndDelete();
  }

  /**
   * @deprecated Use {@link PersistentHashMap#PersistentHashMap(Path, KeyDescriptor, DataExternalizer)}
   */
  @Deprecated
  public PersistentHashMap(@NonNull File file,
                           @NonNull KeyDescriptor<Key> keyDescriptor,
                           @NonNull DataExternalizer<Value> valueExternalizer) throws IOException {
    this(newBuilder(file.toPath(), keyDescriptor, valueExternalizer), true);
  }

  public PersistentHashMap(@NonNull Path file,
                           @NonNull KeyDescriptor<Key> keyDescriptor,
                           @NonNull DataExternalizer<Value> valueExternalizer) throws IOException {
    this(newBuilder(file, keyDescriptor, valueExternalizer), true);
  }

  public PersistentHashMap(@NonNull Path file,
                           @NonNull KeyDescriptor<Key> keyDescriptor,
                           @NonNull DataExternalizer<Value> valueExternalizer,
                           int initialSize) throws IOException {
    this(newBuilder(file, keyDescriptor, valueExternalizer).withInitialSize(initialSize), true);
  }

  public PersistentHashMap(@NonNull Path file,
                           @NonNull KeyDescriptor<Key> keyDescriptor,
                           @NonNull DataExternalizer<Value> valueExternalizer,
                           int initialSize,
                           int version) throws IOException {
    this(newBuilder(file, keyDescriptor, valueExternalizer).withInitialSize(initialSize).withVersion(version), true);
  }

  public PersistentHashMap(@NonNull Path file,
                           @NonNull KeyDescriptor<Key> keyDescriptor,
                           @NonNull DataExternalizer<Value> valueExternalizer,
                           int initialSize,
                           int version,
                           @Nullable StorageLockContext lockContext) throws IOException {
    this(newBuilder(file, keyDescriptor, valueExternalizer).withInitialSize(initialSize).withVersion(version)
           .withStorageLockContext(lockContext), true);
  }

  public void dropMemoryCaches() {
    force();
  }

  @Override
  public void put(Key key, Value value) throws IOException {
    myImpl.put(key, value);
  }

  public @NonNull PersistentMapBase<Key, Value> getImpl() {
    return myImpl;
  }

  /**
   * @deprecated please use {@link AppendablePersistentMap.ValueDataAppender}
   */
  @Deprecated
  @SuppressWarnings("DeprecatedIsStillUsed")
  public interface ValueDataAppender extends AppendablePersistentMap.ValueDataAppender {
  }

  /**
   * @deprecated please use {@link AppendablePersistentMap.ValueDataAppender} as the second parameter
   */
  @Deprecated
  @SuppressWarnings("LambdaUnfriendlyMethodOverload")
  public void appendData(Key key, @NonNull ValueDataAppender appender) throws IOException {
    myImpl.appendData(key, appender);
  }

  /**
   * Appends value chunk from specified appender to key's value.
   * Important use note: value externalizer used by this map should process all bytes from DataInput during deserialization and make sure
   * that deserialized value is consistent with value chunks appended.
   * E.g. Value can be Set of String and individual Strings can be appended with this method for particular key, when {@link #get(Object)} will
   * be eventually called for the key, deserializer will read all bytes retrieving Strings and collecting them into Set
   */
  @Override
  public void appendData(Key key, @NonNull AppendablePersistentMap.ValueDataAppender appender) throws IOException {
    myImpl.appendData(key, appender);
  }

  /**
   * Process all keys registered in the map. Note that keys which were removed after
   * {@link PersistentMapImpl#compact()} call will be processed as well. Use
   * {@link #processKeysWithExistingMapping(Processor)} to process only keys with existing mappings
   */
  @Override
  public boolean processKeys(@NonNull Processor<? super Key> processor) throws IOException {
    return myImpl.processKeys(processor);
  }

  @Override
  public boolean isClosed() {
    return myImpl.isClosed();
  }

  @Override
  public boolean isDirty() {
    return myImpl.isDirty();
  }

  @Override
  public void markDirty() throws IOException {
    myImpl.markDirty();
  }

  public void markCorrupted() {
    myImpl.markCorrupted();
  }

  /**
   * @deprecated use {@link PersistentHashMap#processKeysWithExistingMapping(Processor)} instead.
   */
  @Deprecated
  @NonNull
  public Collection<Key> getAllKeysWithExistingMapping() throws IOException {
    List<Key> result = new ArrayList<>();
    myImpl.processExistingKeys(new CommonProcessors.CollectProcessor<>(result));
    return result;
  }

  public void consumeKeysWithExistingMapping(@NonNull Consumer<? super Key> consumer) throws IOException {
    myImpl.processExistingKeys(key -> {
      consumer.accept(key);
      return true;
    });
  }

  public boolean processKeysWithExistingMapping(@NonNull Processor<? super Key> processor) throws IOException {
    return myImpl.processExistingKeys(processor);
  }

  @Override
  public Value get(Key key) throws IOException {
    return myImpl.get(key);
  }

  @Override
  public boolean containsMapping(Key key) throws IOException {
    return myImpl.containsKey(key);
  }

  @Override
  public void remove(Key key) throws IOException {
    myImpl.remove(key);
  }

  @Override
  public void force() {
    try {
      myImpl.force();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int keysCountApproximately() {
    return myImpl.keysCount();
  }

  @Override
  public void close() throws IOException {
    myImpl.close();
  }

  @Override
  public String toString() {
    return myImpl.toString();
  }

  /**
   * Method creates 'canonical' PHMap by copying the content of originalMap into canonicalMap (which assumed to be
   * empty).
   * <br/>
   * PHMap binary (on-disk) representation could be different even for the same key-value content because
   * of different order of inserts/deletes/updates. This method tries to generate PHMap with 'canonical'
   * binary content. It is done by copying entries to canonicalMap in some stable order, defined by
   * stableKeysSorter, which guarantees canonicalMap to have a fixed binary representation on disk.
   *
   * @param stableKeysSorter   function to sort a List of keys.
   *                           Must provide stable sort -- i.e. a same set
   *                           of keys must always come out sorted in the same order, regardless of their
   *                           original order
   * @param targetCanonicalMap out-parameter: empty map of the same type as originalMap (i.e. suitable as
   *                           receiver of keys-values from originalMap)
   */
  public static <K, V> PersistentHashMap<K, V> canonicalize(final @NonNull PersistentHashMap<K, V> originalMap,
                                                            final @NonNull /* @OutParam */ PersistentHashMap<K, V> targetCanonicalMap,
                                                            final @NonNull Function<? super List<K>, ? extends List<K>> stableKeysSorter,
                                                            final @NonNull Function<? super V, ? extends V> valueCanonicalizer) throws IOException {
    PersistentMapBase.canonicalize(
      originalMap.myImpl,
      targetCanonicalMap.myImpl,
      stableKeysSorter,
      valueCanonicalizer
    );
    return targetCanonicalMap;
  }
}