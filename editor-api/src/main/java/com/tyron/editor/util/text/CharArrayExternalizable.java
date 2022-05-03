package com.tyron.editor.util.text;

import org.jetbrains.annotations.NotNull;

/**
 * A char sequence that supports fast copying of its full or partial contents to a char array. May be useful for performance optimizations
 * @see CharSequenceBackedByArray
 * @see CharArrayUtil#getChars(CharSequence, char[], int)
 */
public interface CharArrayExternalizable extends CharSequence {

  /**
   * Copies own character sub-sequence to the given array
   * @param start the index where to start taking chars from in this sequence
   * @param end the index where to end taking chars in this sequence
   * @param dest the array to put characters into
   * @param destPos the index where to put the characters in the dest array
   */
  void getChars(int start, int end, char @NotNull [] dest, int destPos);
}