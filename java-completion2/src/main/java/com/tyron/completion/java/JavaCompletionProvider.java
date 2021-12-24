package com.tyron.completion.java;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;

public class JavaCompletionProvider extends CompletionProvider {

    @Override
    public String getFileExtension() {
        return "java";
    }

    @Override
    public CompletionList complete(Project project, Module module, File file, String contents, String prefix, int line, int column, long index) {
        if (!(module instanceof JavaModule)) {
            return CompletionList.EMPTY;
        }
        ProgressManager.checkCanceled();

        try {
            JavaCompilerProvider indexProvider = CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
            JavaCompilerService service = indexProvider.getCompiler(project, (JavaModule) module);
            return new com.tyron.completion.java.provider.CompletionProvider(service)
                    .complete(file, contents, index);
        } finally {
            ProgressManager.getInstance().setCanceled(false);
        }
    }
}
