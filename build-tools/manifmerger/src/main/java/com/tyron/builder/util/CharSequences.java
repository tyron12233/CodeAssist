package com.tyron.builder.util;

import org.jetbrains.annotations.NotNull;

import com.google.common.base.Charsets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Arrays;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * A number of utility methods around {@link CharSequence} handling, which
 * adds methods that are available on Strings (such as {@code indexOf},
 * {@code startsWith} and {@code regionMatches} and provides equivalent methods
 * for character sequences.
 * <p>
 * <b>NOTE: This is not a public or final API; if you rely on this be prepared
 * to adjust your code for the next tools release.</b>
 */
public class CharSequences {

    public static int indexOf(@NotNull CharSequence sequence, char c) {
        return indexOf(sequence, c, 0);
    }

    public static int indexOf(@NotNull CharSequence sequence, char c, int start) {
        for (int i = start; i < sequence.length(); i++) {
            if (sequence.charAt(i) == c) {
                return i;
            }
        }

        return -1;
    }

    public static int lastIndexOf(@NotNull CharSequence haystack, @NotNull String needle,
                                  int start) {
        int length = haystack.length();

        int needleLength = needle.length();
        if (needleLength <= length && start >= 0) {
            if (needleLength > 0) {
                if (start > length - needleLength) {
                    start = length - needleLength;
                }
                char firstChar = needle.charAt(0);
                while (true) {
                    int i = lastIndexOf(haystack, firstChar, start);
                    if (i == -1) {
                        return -1;
                    }
                    int o1 = i, o2 = 0;

                    //noinspection StatementWithEmptyBody
                    while (++o2 < needleLength && haystack.charAt(++o1) == needle.charAt(o2)) {
                    }
                    if (o2 == needleLength) {
                        return i;
                    }
                    start = i - 1;
                }
            }
            return start < length ? start : length;
        }

        return -1;
    }

    public static int lastIndexOf(@NotNull CharSequence sequence, char c) {
        return lastIndexOf(sequence, c, sequence.length());
    }

    public static int lastIndexOf(@NotNull CharSequence sequence, int c, int start) {
        int length = sequence.length();
        if (start >= 0) {
            if (start >= length) {
                start = length - 1;
            }

            for (int i = start; i >= 0; i--) {
                if (sequence.charAt(i) == c) {
                    return i;
                }
            }
        }

        return -1;
    }

    public static int lastIndexOf(@NotNull CharSequence haystack, @NotNull String needle) {
        return lastIndexOf(haystack, needle, haystack.length());
    }

    public static boolean regionMatches(
            @NotNull CharSequence sequence,
            int thisStart,
            @NotNull CharSequence string,
            int start,
            int length) {
        if (start < 0 || string.length() - start < length) {
            return false;
        }
        if (thisStart < 0 || sequence.length() - thisStart < length) {
            return false;
        }
        if (length <= 0) {
            return true;
        }
        for (int i = 0; i < length; ++i) {
            if (sequence.charAt(thisStart + i) != string.charAt(start + i)) {
                return false;
            }
        }
        return true;
    }

    public static boolean regionMatches(
            @NotNull CharSequence sequence,
            boolean ignoreCase,
            int thisStart,
            @NotNull CharSequence string,
            int start,
            int length) {
        if (!ignoreCase) {
            return regionMatches(sequence, thisStart, string, start, length);
        }
        if (thisStart < 0 || length > sequence.length() - thisStart) {
            return false;
        }
        if (start < 0 || length > string.length() - start) {
            return false;
        }
        int end = thisStart + length;
        while (thisStart < end) {
            char c1 = sequence.charAt(thisStart++);
            char c2 = string.charAt(start++);
            if (c1 != c2 && foldCase(c1) != foldCase(c2)) {
                return false;
            }
        }
        return true;
    }

    private static char foldCase(char ch) {
        if (ch < 128) {
            if ('A' <= ch && ch <= 'Z') {
                return (char) (ch + ('a' - 'A'));
            }
            return ch;
        }
        return Character.toLowerCase(Character.toUpperCase(ch));
    }

    public static boolean startsWith(@NotNull CharSequence sequence, @NotNull CharSequence prefix) {
        return startsWith(sequence, prefix, 0);
    }

    public static boolean startsWith(@NotNull CharSequence sequence, @NotNull CharSequence prefix,
                                     int start) {
        int sequenceLength = sequence.length();
        int prefixLength = prefix.length();
        if (sequenceLength < start + prefixLength) {
            return false;
        }

        for (int i = start, j = 0; j < prefixLength; i++, j++) {
            if (sequence.charAt(i) != prefix.charAt(j)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the given character sequence ends with the given suffix
     *
     * @param sequence      the sequence to check
     * @param suffix        the suffix to check for
     * @param caseSensitive whether the check should be case sensitive
     * @return true if the sequence ends with the given suffix
     */
    public static boolean endsWith(@NotNull CharSequence sequence, @NotNull CharSequence suffix,
                                   boolean caseSensitive) {
        if (suffix.length() > sequence.length()) {
            return false;
        }

        int suffixLength = suffix.length();
        int sequenceLength = sequence.length();

        for (int i = sequenceLength - suffixLength, j = 0; i < sequenceLength; i++, j++) {
            char c1 = sequence.charAt(i);
            char c2 = suffix.charAt(j);
            if (c1 != c2) {
                if (caseSensitive) {
                    return false;
                } else if (Character.toLowerCase(c1) != Character.toLowerCase(c2)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Returns true if the given sequence contains any upper case characters
     *
     * @param s the sequence to test
     * @return true if there are any upper case characters in the string
     */
    public static boolean containsUpperCase(@Nullable CharSequence s) {
        if (s != null) {
            for (int i = 0, n = s.length(); i < n; i++) {
                if (Character.isUpperCase(s.charAt(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    public static int indexOf(@NotNull CharSequence haystack, @NotNull CharSequence needle) {
        return indexOf(haystack, needle, 0);
    }

    public static int indexOf(
            @NotNull CharSequence haystack, @NotNull CharSequence needle, int start) {
        int needleLength = needle.length();
        if (needleLength == 0) {
            return start;
        }

        char first = needle.charAt(0);

        if (needleLength == 1) {
            return indexOf(haystack, first, start);
        }

        search:
        for (int i = start, max = haystack.length() - needleLength; i <= max; i++) {
            if (haystack.charAt(i) == first) {
                for (int h = i + 1, n = 1; n < needleLength; h++, n++) {
                    if (haystack.charAt(h) != needle.charAt(n)) {
                        continue search;
                    }
                }
                return i;
            }
        }

        return -1;
    }

    /** Similar to {@link String#indexOf(int, int)} but with case insensitive comparison. */
    public static int indexOfIgnoreCase(
            @NotNull CharSequence where, @NotNull CharSequence what, int fromIndex) {
        int targetCount = what.length();
        int sourceCount = where.length();

        if (fromIndex >= sourceCount) {
            return targetCount == 0 ? sourceCount : -1;
        }

        if (fromIndex < 0) {
            fromIndex = 0;
        }

        if (targetCount == 0) {
            return fromIndex;
        }

        char first = what.charAt(0);
        int max = sourceCount - targetCount;

        for (int i = fromIndex; i <= max; i++) {
            /* Look for first character. */
            if (!charsEqualIgnoreCase(where.charAt(i), first)) {
                //noinspection StatementWithEmptyBody,AssignmentToForLoopParameter
                while (++i <= max && !charsEqualIgnoreCase(where.charAt(i), first)) {}
            }

            /* Found first character, now look at the rest of "what". */
            if (i <= max) {
                int j = i + 1;
                int end = j + targetCount - 1;
                //noinspection StatementWithEmptyBody
                for (int k = 1;
                     j < end && charsEqualIgnoreCase(where.charAt(j), what.charAt(k));
                     j++, k++) {}

                if (j == end) {
                    /* Found whole string. */
                    return i;
                }
            }
        }

        return -1;
    }

    private static boolean charsEqualIgnoreCase(char c1, char c2) {
        // Conversion to upper case alone is not sufficient, for example for Georgian alphabet.
        return toUpperCase(c1) == toUpperCase(c2) || toLowerCase(c1) == toLowerCase(c2);
    }

    /**
     * Converts a character to upper case. A slightly optimized version of
     * {@link Character#toUpperCase(char)}.
     */
    public static char toUpperCase(char c) {
        if (c < 'a') return c;
        if (c <= 'z') return (char) (c + ('A' - 'a'));
        return Character.toUpperCase(c);
    }

    /**
     * Converts a character to lower case. A slightly optimized version of
     * {@link Character#toLowerCase(char)}.
     */
    public static char toLowerCase(char c) {
        if (c < 'A' || c >= 'a' && c <= 'z') return c;
        if (c <= 'Z') return (char) (c + ('a' - 'A'));
        return Character.toLowerCase(c);
    }

    @NotNull
    public static CharSequence createSequence(@NotNull char[] data) {
        return new ArrayBackedCharSequence(data);
    }

    @NotNull
    public static CharSequence createSequence(@NotNull char[] data, int offset, int length) {
        return new ArrayBackedCharSequence(data, offset, length);
    }

    @NotNull
    public static char[] getCharArray(@NotNull CharSequence sequence) {
        if (sequence instanceof ArrayBackedCharSequence) {
            return ((ArrayBackedCharSequence)sequence).getCharArray();
        }

        return sequence.toString().toCharArray();
    }

    /**
     * The {@link CharSequenceReader} returned by this method is intended for single-thread use
     * only.
     *
     * @param data the character sequence to read
     * @param stripBom whether a byte order mark at the beginning of the charachter sequence should
     *     be skipped if present
     * @return the reader obtaining its data from the given characher sequence
     */
    @NotNull
    public static CharSequenceReader getReader(@NotNull CharSequence data, boolean stripBom) {
        CharSequenceReader reader = new CharSequenceReader(data);
        if (stripBom) {
            if (data.length() > 0 && data.charAt(0) == '\uFEFF') {
                // Skip BOM
                //noinspection ResultOfMethodCallIgnored
                reader.read();
            }
        }

        return reader;
    }

    @Nullable
    public static Document parseDocumentSilently(@NotNull CharSequence xml, boolean namespaceAware) {
        try {
            Reader reader = getReader(xml, true);
            return XmlUtils.parseDocument(reader, namespaceAware);
        } catch (SAXException | IOException e) {
            // This method is deliberately silent; will return null.
        }

        return null;
    }

    @NotNull
    public static InputStream getInputStream(@NotNull CharSequence text) {
        return new ByteArrayInputStream(text.toString().getBytes(Charsets.UTF_8));
    }

    /**
     * A {@link CharSequence} intended for use by lint; it is a char[]-backed
     * {@linkplain CharSequence} which can provide its backing array to lint
     * (which is useful to avoid having duplicated data, since for example the
     * ECJ-based backend needs char[] instances of the source files instead
     * of Strings, and the String class always insists on having its own
     * private copy of the char array.
     */
    private static class ArrayBackedCharSequence implements CharSequence {
        public final char[] data;
        private final int offset;
        private final int length;

        public ArrayBackedCharSequence(@NotNull char[] data) {
            this(data, 0, data.length);
        }

        public ArrayBackedCharSequence(@NotNull char[] data, int offset, int length) {
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        @NotNull
        public char[] getCharArray() {
            if (offset == 0 && length == data.length) {
                return data;
            } else {
                return Arrays.copyOfRange(data, offset, offset + length);
            }
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            return data[offset + index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new ArrayBackedCharSequence(data, offset + start, end - start);
        }

        @NotNull
        @Override
        public String toString() {
            return new String(data, offset, length);
        }
    }
}
