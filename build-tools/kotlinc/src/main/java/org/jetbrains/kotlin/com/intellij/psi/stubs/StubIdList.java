package org.jetbrains.kotlin.com.intellij.psi.stubs;

import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * List of non-negative ints, monotonically increasing, optimized for one int case (90% of our lists are one-element)
 */
//@ApiStatus.Internal
//@Debug.Renderer(text = "myArray == null ? \"[\" + myData + \"]\" : java.util.Arrays.toString(myArray)")
public final class StubIdList {
  private final int myData;
  private final int[] myArray;

  public StubIdList() {
    myData = -1;
    myArray = null;
  }

  public StubIdList(int value) {
    assert value >= 0;
    myData = value;
    myArray = null;
  }

  public StubIdList(int [] array, int size) {
    myArray = array;
    myData = size;
  }

  @Override
  public int hashCode() {
      if (myArray == null) {
          return myData;
      }
    int value = 0;
    for(int i = 0; i < myData; ++i) {
      value = value * 37 + myArray[i];
    }
    return value;
  }

  @Override
  public boolean equals(Object obj) {
      if (obj == this) {
          return true;
      }
      if (!(obj instanceof StubIdList)) {
          return false;
      }
      StubIdList other = (StubIdList) obj;
      if (myArray == null) {
      return other.myArray == null && other.myData == myData;
    }
      if (other.myArray == null || myData != other.myData) {
          return false;
      }
    for(int i = 0; i < myData; ++i) {
        if (myArray[i] != other.myArray[i]) {
            return false;
        }
    }
    return true;
  }

  public int size() {
    return myArray == null ? myData >= 0 ? 1 : 0: myData;
  }

  public int get(int i) {
    if (myArray == null) {
      assert myData >= 0;
        if (i == 0) {
            return myData;
        }
      throw new IncorrectOperationException();
    }
      if (i >= myData) {
          throw new IncorrectOperationException();
      }
    return myArray[i];
  }

  @Override
  public String toString() {
    return Arrays.toString(IntStream.range(0, size()).map(this::get).toArray());
  }
}