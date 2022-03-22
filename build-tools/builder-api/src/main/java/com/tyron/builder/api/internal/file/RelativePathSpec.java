package com.tyron.builder.api.internal.file;


import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.pattern.PatternMatcher;

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