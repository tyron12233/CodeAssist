package com.tyron.builder.dexing;

import com.android.SdkConstants;

import org.jetbrains.annotations.NotNull;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/** Helper methods for creating {@link ClassFileInput} instances. */
public class ClassFileInputs {

    static final PathMatcher jarMatcher =
            FileSystems.getDefault().getPathMatcher("glob:**" + SdkConstants.DOT_JAR);

    private ClassFileInputs() {
        // empty
    }

    /**
     * Creates a {@link com.android.builder.dexing.ClassFileInput} by analyzing the specified root
     * path. It supports discovery of .class files in directories and jar files, while omitting the
     * ones that do not satisfy the specified predicate.
     *
     * <p>In case the path ends with .jar, all .class files in in will be kept and added to the
     * {@link ClassFileInput} object that is created.
     *
     * <p>Otherwise, the root path will be processed as a directory, and all .class files in it will
     * be processed.
     *
     * @param rootPath root path to analyze, jar or a directory
     * @return input {@link ClassFileInput} that provides a list of .class files to process
     */
    @NotNull
    public static ClassFileInput fromPath(@NotNull Path rootPath) {
        if (jarMatcher.matches(rootPath)) {
            return new JarClassFileInput(rootPath);
        } else {
            return new DirectoryBasedClassFileInput(rootPath);
        }
    }
}