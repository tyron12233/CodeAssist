package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.io.AbstractStringEnumerator;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.UnaryOperator;

//@ApiStatus.Internal
final class FileLocalStringEnumerator implements AbstractStringEnumerator {
  private final Object2IntMap<String> myEnumerates;
  private final ArrayList<String> myStrings = new ArrayList<>();

  FileLocalStringEnumerator(boolean forSavingStub) {
    myEnumerates = forSavingStub ? new Object2IntOpenHashMap<>() : null;
  }

  @Override
  public int enumerate(@Nullable String value) {
    if (value == null) {
      return 0;
    }
    assert myEnumerates != null; // enumerate possible only when writing stub
    int i = myEnumerates.getInt(value);
    if (i == 0) {
      myEnumerates.put(value, i = myStrings.size() + 1);
      myStrings.add(value);
    }
    return i;
  }

  @Override
  public String valueOf(int idx) {
      if (idx == 0) {
          return null;
      }
    return myStrings.get(idx - 1);
  }

  void write(@NonNull DataOutput stream) throws IOException {
    assert myEnumerates != null;
    DataInputOutputUtil.writeINT(stream, myStrings.size());
    for(String s: myStrings) {
      IOUtil.writeUTF(stream, s);
    }
  }

  @Override
  public void markCorrupted() {
  }

  @Override
  public void close() throws IOException {
  }

  public boolean isDirty() {
    return false;
  }

  @Override
  public void force() {
  }

  void read(@NonNull DataInput stream, @NonNull UnaryOperator<String> mapping) throws IOException {
    int numberOfStrings = DataInputOutputUtil.readINT(stream);
    myStrings.ensureCapacity(myStrings.size() + numberOfStrings);
    for (int i = 0; i < numberOfStrings; i++) {
      myStrings.add(mapping.apply(IOUtil.readUTF(stream)));
    }
  }
}