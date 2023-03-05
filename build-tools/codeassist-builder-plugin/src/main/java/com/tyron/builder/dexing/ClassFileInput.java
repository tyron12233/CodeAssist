package com.tyron.builder.dexing;

import com.android.SdkConstants;

import org.gradle.util.internal.GFileUtils;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * This represents input containing .class files. It is used as an input for the dexing phase.All
 * files are specified relative to some base directory, or .jar containing them. This is necessary
 * in order to process the packages properly.
 *
 * <p>When using instances of {@link ClassFileInput} make sure that you invoke {@link #close()}
 * after you are done using it.
 */
public interface ClassFileInput extends Closeable {

    /** Accepts Unix-style or Windows-style absolute or relative path. */
    public Predicate<String> CLASS_MATCHER =
            s -> {
                String lowerCase = GFileUtils.toSystemIndependentPath(s.toLowerCase(Locale.US));
                if (!lowerCase.endsWith(SdkConstants.DOT_CLASS)) {
                    return false;
                }

                if (lowerCase.equals("module-info.class")
                        || lowerCase.endsWith("/module-info.class")) {
                    return false;
                }

                return !lowerCase.startsWith("/meta-inf/") && !lowerCase.startsWith("meta-inf/");
            };

    /**
     * @param filter filter specify which files should be part of the class input
     * @return a {@link Stream} for all the entries that satisfies the passed filter.
     * @throws IOException if the jar/directory cannot be read correctly.
     */
    Stream<ClassFileEntry> entries(BiPredicate<Path, String> filter) throws IOException;

    /** @return the root {@link Path} of this {@link ClassFileInput}. */
    Path getPath();

}