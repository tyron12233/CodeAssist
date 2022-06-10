package org.gradle.api.internal.file;


import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RelativePath;
import org.gradle.internal.file.pattern.PatternMatcher;

import java.util.function.Predicate;

public class RelativePathSpec implements Predicate<FileTreeElement> {
    private final PatternMatcher matcher;

    public RelativePathSpec(PatternMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public boolean test(FileTreeElement element) {
        RelativePath relativePath = element.getRelativePath();
        return matcher.test(relativePath.getSegments(), relativePath.isFile());
    }
}