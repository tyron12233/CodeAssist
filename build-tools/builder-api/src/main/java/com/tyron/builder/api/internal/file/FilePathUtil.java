package com.tyron.builder.api.internal.file;

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
}
