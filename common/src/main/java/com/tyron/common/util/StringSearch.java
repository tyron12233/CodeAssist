package com.tyron.common.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringSearch {

    // pattern is the string that we are searching for in the text.
    private final byte[] pattern;

    // badCharSkip[b] contains the distance between the last byte of pattern
    // and the rightmost occurrence of b in pattern. If b is not in pattern,
    // badCharSkip[b] is len(pattern).
    //
    // Whenever a mismatch is found with byte b in the text, we can safely
    // shift the matching frame at least badCharSkip[b] until the next time
    // the matching char could be in alignment.
    // TODO 256 is not coloring
    private final int[] badCharSkip = new int[256];

    // goodSuffixSkip[i] defines how far we can shift the matching frame given
    // that the suffix pattern[i+1:] matches, but the byte pattern[i] does
    // not. There are two cases to consider:
    //
    // 1. The matched suffix occurs elsewhere in pattern (with a different
    // byte preceding it that we might possibly match). In this case, we can
    // shift the matching frame to align with the next suffix chunk. For
    // example, the pattern "mississi" has the suffix "issi" next occurring
    // (in right-to-left order) at index 1, so goodSuffixSkip[3] ==
    // shift+len(suffix) == 3+4 == 7.
    //
    // 2. If the matched suffix does not occur elsewhere in pattern, then the
    // matching frame may share part of its prefix with the end of the
    // matching suffix. In this case, goodSuffixSkip[i] will contain how far
    // to shift the frame to align this portion of the prefix to the
    // suffix. For example, in the pattern "abcxxxabc", when the first
    // mismatch from the back is found to be in position 3, the matching
    // suffix "xxabc" is not found elsewhere in the pattern. However, its
    // rightmost "abc" (at position 6) is a prefix of the whole pattern, so
    // goodSuffixSkip[3] == shift+len(suffix) == 6+5 == 11.
    private final int[] goodSuffixSkip;

    StringSearch(String patternSting) {
        this.pattern = patternSting.getBytes();
        this.goodSuffixSkip = new int[pattern.length];

        // last is the index of the last character in the pattern.
        int last = pattern.length - 1;

        // Build bad character table.
        // Bytes not in the pattern can skip one pattern's length.
        Arrays.fill(badCharSkip, pattern.length);
        // The loop condition is < instead of <= so that the last byte does not
        // have a zero distance to itself. Finding this byte out of place implies
        // that it is not in the last position.
        for (int i = 0; i < last; i++) {
            badCharSkip[pattern[i] + 128] = last - i;
        }

        // Build good suffix table.
        // First pass: set each value to the next index which starts a prefix of
        // pattern.
        int lastPrefix = last;
        for (int i = last; i >= 0; i--) {
            if (hasPrefix(pattern, new Slice(pattern, i + 1))) {
                lastPrefix = i + 1;
            }
            // lastPrefix is the shift, and (last-i) is len(suffix).
            goodSuffixSkip[i] = lastPrefix + last - i;
        }
        // Second pass: find repeats of pattern's suffix starting from the front.
        for (int i = 0; i < last; i++) {
            int lenSuffix = longestCommonSuffix(pattern, new Slice(pattern, 1, i + 1));
            if (pattern[i - lenSuffix] != pattern[last - lenSuffix]) {
                // (last-i) is the shift, and lenSuffix is len(suffix).
                goodSuffixSkip[last - lenSuffix] = lenSuffix + last - i;
            }
        }
    }

    int next(String text) {
        return next(text.getBytes());
    }

    private int next(byte[] text) {
        return next(ByteBuffer.wrap(text));
    }

    private int next(ByteBuffer text) {
        return next(text, 0);
    }

    private int next(ByteBuffer text, int startingAfter) {
        int i = startingAfter + pattern.length - 1;
        while (i < text.limit()) {
            // Compare backwards from the end until the first unmatching character.
            int j = pattern.length - 1;
            while (j >= 0 && text.get(i) == pattern[j]) {
                i--;
                j--;
            }
            if (j < 0) {
                return i + 1; // match
            }
            i += Math.max(badCharSkip[text.get(i) + 128], goodSuffixSkip[j]);
        }
        return -1;
    }

    private boolean hasPrefix(byte[] s, Slice prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if (s[i] != prefix.get(i)) {
                return false;
            }
        }
        return true;
    }


    private int longestCommonSuffix(byte[] a, Slice b) {
        int i = 0;
        for (; i < a.length && i < b.length(); i++) {
            if (a[a.length - 1 - i] != b.get(b.length() - 1 - i)) {
                break;
            }
        }
        return i;
    }

    int nextWord(String text) {
        return nextWord(text.getBytes());
    }

    private int nextWord(byte[] text) {
        return nextWord(ByteBuffer.wrap(text));
    }

    private int nextWord(ByteBuffer text) {
        int i = 0;
        while (true) {
            i = next(text, i);
            if (i == -1) {
                return -1;
            }
            if (isWord(text, i)) {
                return i;
            }
            i++;
        }
    }

    public static int endOfLine(CharSequence contents, int cursor) {
        while (cursor < contents.length()) {
            char c = contents.charAt(cursor);
            if (c == '\r' || c == '\n') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private boolean isWordChar(byte b) {
        char c = (char) (b + 128);
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '$' || c == '_';
    }

    private boolean startsWord(ByteBuffer text, int offset) {
        if (offset == 0) {
            return true;
        }
        return !isWordChar(text.get(offset - 1));
    }

    private boolean endsWord(ByteBuffer text, int offset) {
        if (offset + 1 >= text.limit()) {
            return true;
        }
        return !isWordChar(text.get(offset + 1));
    }

    private boolean isWord(ByteBuffer text, int offset) {
        return startsWord(text, offset) && endsWord(text, offset + pattern.length - 1);
    }


    public static boolean containsWord(Path java, String query) {
        StringSearch search = new StringSearch(query);
//        if (FileStore.activeDocuments().contains(java)) {
//            var text = FileStore.contents(java).getBytes();
//            return search.nextWord(text) != -1;
//        }
        try (FileChannel channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            int limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            return search.nextWord(SEARCH_BUFFER) != -1;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean matchesPartialName(CharSequence candidate, CharSequence partialName) {
        if (partialName.length() == 1 && partialName.equals(".")) {
            return true;
        }
        if (candidate.length() < partialName.length()) {
            return false;
        }
        for (int i = 0; i < partialName.length(); i++) {
            if (candidate.charAt(i) != partialName.charAt(i)) {
                return false;
            }
        }
        return true;
//        if (candidate.length() > partialName.length()) {
//            candidate = candidate.subSequence(0, partialName.length());
//        }
//        double similarity = similarity(candidate.toString(), partialName.toString());
//        return similarity > 0.5;
    }

    public static boolean matchesPartialNameLowercase(CharSequence candidate,
                                                      CharSequence partialName) {
        if (partialName.length() == 1 && partialName.equals(".")) {
            return true;
        }
        if (candidate.length() < partialName.length()) {
            return false;
        }
        for (int i = 0; i < partialName.length(); i++) {
            if (Character.toLowerCase(candidate.charAt(i)) !=
                Character.toLowerCase(partialName.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String packageName(File file) {
        Pattern packagePattern = Pattern.compile("package\\s+([a-zA_Z][.\\w]*+)(;)?");
        Pattern startOfClass = Pattern.compile("^[\\w ]*class +\\w+");
        try (BufferedReader lines = bufferedReader(file)) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (startOfClass.matcher(line)
                        .find()) {
                    return "";
                }
                Matcher matchPackage = packagePattern.matcher(line);
                if (matchPackage.matches()) {
                    String id = matchPackage.group(1);
                    return id;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // TODO fall back on parsing file
        return "";
    }

    // TODO this doesn't work for inner classes, eliminate
    public static String mostName(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot == -1 ? "" : name.substring(0, lastDot);
    }

    // TODO this doesn't work for inner classes, eliminate
    public static String lastName(String name) {
        int i = name.lastIndexOf('.');
        if (i == -1) {
            return name;
        } else {
            return name.substring(i + 1);
        }
    }

    public static BufferedReader bufferedReader(File file) {
        try {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        } catch (FileNotFoundException e) {
            return new BufferedReader(new StringReader(""));
        }
    }

    public static double similarity(String s1, String s2) {
        String longer = s1, shorter = s2;
        if (s1.length() < s2.length()) { // longer should always have greater length
            longer = s2;
            shorter = s1;
        }

        int longerLength = longer.length();
        if (longerLength == 0) {
            return 1.0; /* both strings are zero length */
        }
        return (longerLength - editDistance(longer, shorter)) / (double) longerLength;
    }

    public static int editDistance(String s1, String s2) {
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();

        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        }
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        return costs[s2.length()];
    }

    private static boolean containsString(Path java, String query) {
        StringSearch search = new StringSearch(query);
//        if (FileStore.activeDocuments().contains(java)) {
//            var text = FileStore.contents(java).getBytes();
//            return search.next(text) != -1;
//        }
        try (FileChannel channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            int limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            return search.next(SEARCH_BUFFER) != -1;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean endsWithParen(CharSequence contents, int cursor) {
        for (int i = cursor; i < contents.length(); i++) {
            if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
                return contents.charAt(i) == '(';
            }
        }
        return false;
    }


    public static String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    public static boolean isQualifiedIdentifierChar(char c) {
        return c == '.' || Character.isJavaIdentifierPart(c);
    }

    public static String qualifiedPartialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private static class Slice {
        private final byte[] target;
        private int from, until;

        int length() {
            return until - from;
        }

        byte get(int i) {
            return target[from + i];
        }

        Slice(byte[] target, int from) {
            this(target, from, target.length);
        }

        Slice(byte[] target, int from, int until) {
            this.target = target;
            this.from = from;
            this.until = until;
        }
    }

    private static final ByteBuffer SEARCH_BUFFER = ByteBuffer.allocateDirect(1024 * 1024);
}
