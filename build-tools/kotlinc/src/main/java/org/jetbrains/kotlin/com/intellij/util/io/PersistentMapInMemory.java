package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PersistentMapInMemory<Key, Value> implements PersistentMapBase<Key, Value> {
  private final Object myLock = new Object();

  private final ConcurrentHashMap<Key, Value> myMap = new ConcurrentHashMap<>();
  private final AtomicBoolean myIsClosed = new AtomicBoolean(false);
  private final DataExternalizer<Value> myValueExternalizer;

  public PersistentMapInMemory(@NonNull PersistentMapBuilder<Key,Value> builder) {
    myValueExternalizer = builder.getValueExternalizer();
  }

  @Override
  public @NonNull DataExternalizer<Value> getValuesExternalizer() {
    return myValueExternalizer;
  }

  public @NonNull Object getDataAccessLock() {
    return myLock;
  }

  @Override
  public void closeAndDelete() {
    myMap.clear();
  }

  @Override
  public int keysCount() {
    return myMap.size();
  }

  @Override
  public void put(Key key, Value value) throws IOException {
    myMap.put(key, value);
  }

  @Override
  public boolean processKeys(@NonNull Processor<? super Key> processor) throws IOException {
    return processExistingKeys(processor);
  }

  @Override
  public boolean isDirty() {
    return false;
  }

  @Override
  public void markDirty() throws IOException {
  }

  @Override
  public boolean processExistingKeys(@NonNull Processor<? super Key> processor) throws IOException {
    for (Key key : myMap.keySet()) {
        if (!processor.process(key)) {
            return false;
        }
    }
    return true;
  }

  @Override
  public Value get(Key key) throws IOException {
    return myMap.get(key);
  }

  @Override
  public boolean containsKey(Key key) throws IOException {
    return myMap.containsKey(key);
  }

  @Override
  public void remove(Key key) throws IOException {
    myMap.remove(key);
  }

  @Override
  public void force() {

  }

  @Override
  public boolean isClosed() {
    return myIsClosed.get();
  }

  @Override
  public void close() throws IOException {
    myIsClosed.set(true);
  }
}