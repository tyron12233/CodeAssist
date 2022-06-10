package org.gradle.api.internal.file.copy;

import org.gradle.api.Action;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.RelativePath;
import org.gradle.internal.file.pattern.PatternMatcher;

public class MatchingCopyAction implements Action<FileCopyDetails> {

    private final PatternMatcher matcher;

    private final Action<? super FileCopyDetails> toApply;

    public MatchingCopyAction(PatternMatcher matcher, Action<? super FileCopyDetails> toApply) {
        this.matcher = matcher;
        this.toApply = toApply;
    }

    @Override
    public void execute(FileCopyDetails details) {
        RelativePath relativeSourcePath = details.getRelativeSourcePath();
        if (matcher.test(relativeSourcePath.getSegments(), relativeSourcePath.isFile())) {
            toApply.execute(details);
        }
    }

}
