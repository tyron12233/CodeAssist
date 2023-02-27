package org.jetbrains.kotlin.com.intellij.openapi.util;

public final class IntRef {
  private int myValue;

  public IntRef() {
    this(0);
  }

  public IntRef(int value) {
    myValue = value;
  }

  public int get() {
    return myValue;
  }

  public void set(int value) {
    myValue = value;
  }

  public void inc() {
    myValue++;
  }

  @Override
  public String toString() {
    return "IntRef(" + myValue + ")";
  }
}