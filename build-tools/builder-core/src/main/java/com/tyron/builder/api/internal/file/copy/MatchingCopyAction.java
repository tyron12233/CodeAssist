package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.internal.file.pattern.PatternMatcher;

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
