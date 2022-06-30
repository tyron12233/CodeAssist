package com.tyron.builder.internal.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public class FilePathUtil {

    private static final String[] EMPTY_STRING_ARRAY = {};
    // On Windows, / and \ are separators, on Unix only / is a separator.
    private static final String FILE_PATH_SEPARATORS = File.separatorChar != '/' ? ("/" + File.separator) : File.separator;

    private FilePathUtil() {
    }

    public static String[] getPathSegments(String path) {
        StringTokenizer tokenizer = new StringTokenizer(path, FILE_PATH_SEPARATORS);
        List<String> segments = new ArrayList<String>();
        while (tokenizer.hasMoreElements()) {
            segments.add(tokenizer.nextToken());
        }
        return segments.toArray(EMPTY_STRING_ARRAY);
    }


    /**
     * Does not include the file separator.
     */
    public static int sizeOfCommonPrefix(String path1, String path2, int offset) {
        return sizeOfCommonPrefix(path1, path2, offset, File.separatorChar);
    }

    /**
     * Does not include the separator char.
     */
    public static int sizeOfCommonPrefix(String path1, String path2, int offset, char separatorChar) {
        int pos = 0;
        int lastSeparator = 0;
        int maxPos = Math.min(path1.length(), path2.length() - offset);
        for (; pos < maxPos; pos++) {
            if (path1.charAt(pos) != path2.charAt(pos + offset)) {
                break;
            }
            if (path1.charAt(pos) == separatorChar) {
                lastSeparator = pos;
            }
        }
        if (pos == maxPos) {
            if (path1.length() == path2.length() - offset) {
                return pos;
            }
            if (pos < path1.length() && path1.charAt(pos) == separatorChar) {
                return pos;
            }
            if (pos < path2.length() - offset && path2.charAt(pos + offset) == separatorChar) {
                return pos;
            }
        }
        return lastSeparator;
    }
}
