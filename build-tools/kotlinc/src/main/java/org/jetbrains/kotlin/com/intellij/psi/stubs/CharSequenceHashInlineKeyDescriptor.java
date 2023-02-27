package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.io.InlineKeyDescriptor;

import java.util.stream.IntStream;

final class CharSequenceHashInlineKeyDescriptor extends InlineKeyDescriptor<CharSequence> {

  final static CharSequenceHashInlineKeyDescriptor INSTANCE = new CharSequenceHashInlineKeyDescriptor();

  @Override
  public CharSequence fromInt(int n) {
    return new HashWrapper(n);
  }

  @Override
  public int toInt(CharSequence s) {
    return s.hashCode();
  }

  private static class HashWrapper implements CharSequence {
    final int hashCode;

    HashWrapper(int hashCode) {
      this.hashCode = hashCode;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int length() {
      throw new UnsupportedOperationException();
    }

    @Override
    public char charAt(int index) {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull IntStream chars() {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull IntStream codePoints() {
      throw new UnsupportedOperationException();
    }
  }
}