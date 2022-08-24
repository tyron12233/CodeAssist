package org.gradle.api.internal.file;


import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RelativePath;
import org.gradle.api.specs.Spec;
import org.gradle.internal.file.pattern.PatternMatcher;

public class RelativePathSpec implements Spec<FileTreeElement> {
    private final PatternMatcher matcher;

    public RelativePathSpec(PatternMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public boolean isSatisfiedBy(FileTreeElement element) {
        RelativePath relativePath = element.getRelativePath();
        return matcher.test(relativePath.getSegments(), relativePath.isFile());
    }
}