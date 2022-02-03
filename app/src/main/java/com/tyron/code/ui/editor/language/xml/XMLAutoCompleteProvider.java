package com.tyron.code.ui.editor.language.xml;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.util.CharSequenceReader;
import com.tyron.code.ui.editor.language.AbstractAutoCompleteProvider;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.util.Stack;
import java.util.stream.Collectors;

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
        return CompletionEngine.getInstance().complete(currentProject, module,
                currentFile, mEditor.getContent().toString(), prefix, line, column,
                mEditor.getCaret().getStart());
    }
}
