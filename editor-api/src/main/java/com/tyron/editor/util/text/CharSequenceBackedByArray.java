package com.tyron.editor.util.text;

import org.jetbrains.annotations.NotNull;

/**
 * A char sequence based on a char array. May be used for performance optimizations.
 * 
 * @author Maxim.Mossienko
 * @see CharArrayExternalizable
 * @see CharArrayUtil#getChars(CharSequence, char[], int)
 * @see CharArrayUtil#fromSequenceWithoutCopying(CharSequence)
 */
public interface CharSequenceBackedByArray extends CharSequence {
  // NOT guaranteed to return the array of the length of the original charSequence.length() - may be more for performance reasons.
  char @NotNull [] getChars();

  void getChars(char @NotNull [] dst, int dstOffset);
}