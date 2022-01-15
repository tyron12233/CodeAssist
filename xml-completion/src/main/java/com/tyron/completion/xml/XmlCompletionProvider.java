package com.tyron.completion.xml;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CompletionList;

import java.io.File;

public class XmlCompletionProvider extends CompletionProvider {

    private static final String EXTENSION = ".xml";

    @Override
    public String getFileExtension() {
        return EXTENSION;
    }

    @Override
    public CompletionList complete(Project project,
                                   Module module, File file,
                                   String contents, String prefix, int line, int column, long index) {
        XmlIndexProvider indexProvider = CompilerService.getInstance().getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize();
        return CompletionList.EMPTY;
    }
}
