package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.DataInputOutputUtilRt;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntList;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.IntUnaryOperator;

//@ApiStatus.Internal
final class IntEnumerator {
  private final Int2IntMap myEnumerates;
  private final IntArrayList myIds;
  private int myNext;

  IntEnumerator() {
    this(true);
  }

  private IntEnumerator(boolean forSavingStub) {
    myEnumerates = forSavingStub ? new Int2IntOpenHashMap(1) : null;
    myIds = new IntArrayList();
  }

  int enumerate(int number) {
    assert myEnumerates != null;
    int i = myEnumerates.get(number);
    if (i == 0) {
      i = myNext;
      myEnumerates.put(number, myNext++);
      myIds.add(number);
    }
    return i;
  }

  int valueOf(int id) {
    return myIds.getInt(id);
  }

  void dump(@NonNull DataOutput stream) throws IOException {
    dump(stream, IntUnaryOperator.identity());
  }

  void dump(@NonNull DataOutput stream, @NonNull IntUnaryOperator idRemapping) throws IOException {
    DataInputOutputUtilRt.writeINT(stream, myIds.size());
    for (int i = 0; i < myIds.size(); i++) {
      int id = myIds.getInt(i);
      int remapped = idRemapping.applyAsInt(id);
      if (remapped == 0) {
        throw new IOException("remapping is not found for " + id);
      }
      DataInputOutputUtilRt.writeINT(stream, remapped);
    }
  }

  static IntEnumerator read(@NonNull DataInput stream) throws IOException {
    int size = DataInputOutputUtilRt.readINT(stream);
    IntEnumerator enumerator = new IntEnumerator(false);
    for (int i = 0; i < size; i++) {
      enumerator.myIds.add(DataInputOutputUtilRt.readINT(stream));
    }
    return enumerator;
  }
}