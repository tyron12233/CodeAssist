package com.tyron.completion;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;

/**
 * Subclass this to provide completions on the given file.
 * <p>
 * Be sure to frequently call {@link ProgressManager#checkCanceled()} for the
 * user to have a smooth experience because the user may be typing fast and operations
 * may be cancelled at that time.
 */
public abstract class CompletionProvider {

    public abstract boolean accept(File file);

    public abstract CompletionList complete(CompletionParameters parameters);
}
