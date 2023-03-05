package org.gradle.util;

import org.gradle.internal.UncheckedException;

import java.io.File;
import java.util.regex.Pattern;

/**
 * This class is only here to maintain binary compatibility with existing plugins.
 *
 * @deprecated Will be removed in Gradle 8.0.
 */
@Deprecated
public class RelativePathUtil {
    /**
     * Returns a relative path from 'from' to 'to'
     *
     * @param from where to calculate from
     * @param to where to calculate to
     * @return The relative path
     */
    public static String relativePath(File from, File to) {
        try {
            return normaliseFileSeparators(from.toPath().relativize(to.toPath()).toString());
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private static String normaliseFileSeparators(String path) {
        return path.replaceAll(Pattern.quote(File.separator), "/");
    }
}
