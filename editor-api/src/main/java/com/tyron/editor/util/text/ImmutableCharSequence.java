package com.tyron.editor.util.text;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public abstract class ImmutableCharSequence implements CharSequence {

  @Contract(pure = true)
  public static CharSequence asImmutable(@NotNull final CharSequence cs) {
    return isImmutable(cs) ? cs : cs.toString();
  }

  private static boolean isImmutable(@NotNull final CharSequence cs) {
    return cs instanceof ImmutableCharSequence;// ||
//           cs instanceof CharSequenceSubSequence && isImmutable(((CharSequenceSubSequence)cs).getBaseSequence());
  }

  @Contract(pure = true)
  public abstract ImmutableCharSequence concat(@NotNull CharSequence sequence);

  @Contract(pure = true)
  public abstract ImmutableCharSequence insert(int index, @NotNull CharSequence seq);

  @Contract(pure = true)
  public abstract ImmutableCharSequence delete(int start, int end);

  @Contract(pure = true)
  public abstract ImmutableCharSequence subtext(int start, int end);

  @Contract(pure = true)
  public ImmutableCharSequence replace(int start, int end, @NotNull CharSequence seq) {
    return delete(start, end).insert(start, seq);
  }

  @NotNull
  @Override
  public abstract String toString();
}