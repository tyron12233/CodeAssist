package com.tyron.code.language.xml;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.language.AbstractAutoCompleteProvider;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import java.io.File;

public class XMLAutoCompleteProvider extends AbstractAutoCompleteProvider {

    private final Editor mEditor;

    public XMLAutoCompleteProvider(Editor editor) {
        mEditor = editor;
    }

    @Override
    public CompletionList getCompletionList(String prefix, int line, int column) {
        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        if (currentProject == null) {
            return null;
        }
        Module module = currentProject.getModule(mEditor.getCurrentFile());
        if (!(module instanceof AndroidModule)) {
            return null;
        }

        File currentFile = mEditor.getCurrentFile();
        if (currentFile == null) {
            return null;
        }
        return CompletionEngine.getInstance().complete(currentProject, module, mEditor,
                currentFile, mEditor.getContent().toString(), prefix, line, column,
                mEditor.getCaret().getStart());
    }
}
