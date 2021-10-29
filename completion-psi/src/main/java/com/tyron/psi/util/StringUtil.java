package com.tyron.psi.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtilRt;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.Strings;

public class StringUtil extends StringUtilRt {

    public static @NotNull String escapeToRegexp(@NotNull String text) {
        final StringBuilder result = new StringBuilder(text.length());
        return escapeToRegexp(text, result).toString();
    }

    public static @NotNull StringBuilder escapeToRegexp(@NotNull CharSequence text, @NotNull StringBuilder builder) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Contract(pure = true)
    public static boolean contains(@NotNull CharSequence sequence, @NotNull CharSequence infix) {
        return org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil.containsAnyChar(sequence.toString(), infix.toString());
    }

    /**
     * Allows to answer if target symbol is contained at given char sequence at {@code [start; end)} interval.
     *
     * @param s     target char sequence to check
     * @param start start offset to use within the given char sequence (inclusive)
     * @param end   end offset to use within the given char sequence (exclusive)
     * @param c     target symbol to check
     * @return {@code true} if given symbol is contained at the target range of the given char sequence;
     * {@code false} otherwise
     */
    @Contract(pure = true)
    public static boolean contains(@NotNull CharSequence s, int start, int end, char c) {
        return Strings.contains(s, start, end, c);
    }

    /**
     * Expirable CharSequence. Very useful to control external library execution time,
     * i.e. when java.util.regex.Pattern match goes out of control.
     */
    public abstract static class BombedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private int i;
        private boolean myDefused;

        public BombedCharSequence(@NotNull CharSequence sequence) {
            delegate = sequence;
        }

        @Override
        public int length() {
            check();
            return delegate.length();
        }

        @Override
        public char charAt(int i) {
            check();
            return delegate.charAt(i);
        }

        protected void check() {
            if (myDefused) {
                return;
            }
            if ((++i & 1023) == 0) {
                checkCanceled();
            }
        }

        public final void defuse() {
            myDefused = true;
        }

        @Override
        public @NotNull
        String toString() {
            check();
            return delegate.toString();
        }

        protected abstract void checkCanceled();

        @Override
        public @NotNull CharSequence subSequence(int i, int i1) {
            check();
            return delegate.subSequence(i, i1);
        }
    }

}
