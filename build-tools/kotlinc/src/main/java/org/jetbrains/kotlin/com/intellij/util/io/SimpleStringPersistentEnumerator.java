package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple version of string enumerator:
 * <p><ul>
 * <li>strings are stored directly in UTF-8 encoding
 * <li>always has synchronized state between disk and memory state
 * </ul>
 */
//@ApiStatus.Internal
public final class SimpleStringPersistentEnumerator implements DataEnumerator<String> {
  private final @NonNull Path myFile;
  private final @NonNull Object2IntMap<String> myInvertedState;
  private final @NonNull Int2ObjectMap<String> myForwardState;

  public SimpleStringPersistentEnumerator(@NonNull Path file) {
    myFile = file;
    Pair<Object2IntMap<String>, Int2ObjectMap<String>> pair = readStorageFromDisk(file);
    myInvertedState = pair.getFirst();
    myForwardState = pair.getSecond();
  }

  public @NonNull Path getFile() {
    return myFile;
  }

  @Override
  public synchronized int enumerate(@Nullable String value) {
    int id = myInvertedState.getInt(value);
    if (id != myInvertedState.defaultReturnValue()) {
      return id;
    }

    if (value != null && StringUtil.containsLineBreak(value)) {
      throw new RuntimeException("SimpleStringPersistentEnumerator doesn't support multi-line strings");
    }

    // do not use myInvertedState.size because enumeration file may have duplicates on different lines
    int n = myForwardState.size() + 1;
    myInvertedState.put(value, n);
    myForwardState.put(n, value);
    writeStorageToDisk(myForwardState, myFile);
    return n;
  }

  public synchronized @NonNull Collection<String> entries() {
    return new ArrayList<>(myInvertedState.keySet());
  }

  public synchronized @NonNull Map<String, Integer> getInvertedState() {
    return Collections.unmodifiableMap(myInvertedState);
  }

  @Override
  @Nullable
  public synchronized String valueOf(int idx) {
    return myForwardState.get(idx);
  }

  public synchronized void forceDiskSync() {
    writeStorageToDisk(myForwardState, myFile);
  }

  public synchronized boolean isEmpty() {
    return myInvertedState.isEmpty();
  }

  public synchronized int getSize(){
    return myInvertedState.size();
  }

  @NonNull
  public String dumpToString() {
    return myInvertedState
      .object2IntEntrySet()
      .stream()
      .sorted(Comparator.comparing(Object2IntMap.Entry::getIntValue))
      .map(e -> e.getKey() + "->" + e.getIntValue()).collect(Collectors.joining("\n"));
  }

  private static @NonNull Pair<Object2IntMap<String>, Int2ObjectMap<String>> readStorageFromDisk(@NonNull Path file) {
    try {
      Object2IntMap<String> nameToIdRegistry = new Object2IntOpenHashMap<>();
      Int2ObjectMap<String> idToNameRegistry = new Int2ObjectOpenHashMap<>();
      List<String> lines = Files.readAllLines(file, Charset.defaultCharset());
      for (int i = 0; i < lines.size(); i++) {
        String name = lines.get(i);
        nameToIdRegistry.put(name, i + 1);
        idToNameRegistry.put(i + 1, name);
      }
      return Pair.create(nameToIdRegistry, idToNameRegistry);
    }
    catch (IOException e) {
      writeStorageToDisk(new Int2ObjectOpenHashMap<>(), file);
      return Pair.create(new Object2IntOpenHashMap<>(), new Int2ObjectOpenHashMap<>());
    }
  }

  private static void writeStorageToDisk(@NonNull Int2ObjectMap<String> forwardIndex, @NonNull Path file) {
    try {
      String[] names = new String[forwardIndex.size()];
      for (ObjectIterator<Int2ObjectMap.Entry<String>> iterator = Int2ObjectMaps.fastIterator(forwardIndex); iterator.hasNext(); ) {
        Int2ObjectMap.Entry<String> entry = iterator.next();
        names[entry.getIntKey() - 1] = entry.getValue();
      }

      Files.createDirectories(file.getParent());
      //FIXME RC: class-level javadoc states values stored in UTF8, but here it is .defaultCharset()!
      Files.write(file, Arrays.asList(names), Charset.defaultCharset());
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}