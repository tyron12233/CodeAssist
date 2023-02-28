package com.tyron.completion.main;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.model.CompletionList;
import com.tyron.legacyEditor.Editor;

import java.io.File;
import java.util.logging.Logger;

/**
 * Main entry point for the completions api.
 */
public class CompletionEngine {

    private static CompletionEngine sInstance = null;

    public static CompletionEngine getInstance() {
        if (sInstance == null) {
            sInstance = new CompletionEngine();
        }
        return sInstance;
    }

    private final Logger logger = IdeLog.getCurrentLogger(this);

    public CompletionEngine() {

    }

    public CompletionList complete(Project project,
                                   Module module,
                                   Editor editor,
                                   File file,
                                   String contents,
                                   String prefix,
                                   int line,
                                   int column,
                                   long index) {
       throw new UnsupportedOperationException();
    }
}
