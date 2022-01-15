package com.tyron.code.ui.editor.language.xml;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.util.CharSequenceReader;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.model.CompletionList;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextAnalyzeResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.widget.CodeEditor;

import java.util.Collections;
import java.util.Stack;
import java.util.stream.Collectors;

public class XMLAutoCompleteProvider implements AutoCompleteProvider {

    private final CodeEditor mEditor;

    public XMLAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix,
                                                     TextAnalyzeResult analyzeResult, int line,
                                                     int column) {
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
        CompletionList complete = CompletionEngine.getInstance().complete(currentProject, module,
                currentFile, mEditor.getText().toString(), prefix, line, column,
                mEditor.getCursor().getLeft());
        return complete.items.stream().map(CompletionItem::new).collect(Collectors.toList());
    }

    private List<CompletionItem> getClosingTagSuggestions() {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new CharSequenceReader(mEditor.getText()));

            Stack<String> stack = new Stack<>();

            int next = parser.nextTag();
            while (true) {
                int type = parser.getEventType();
                switch (type) {
                    case XmlPullParser.START_TAG:
                        stack.push(parser.getName());
                        break;
                    case XmlPullParser.END_TAG:
                        stack.pop();
                        break;
                }

                try {
                    next = parser.nextTag();
                    if (next == XmlPullParser.END_DOCUMENT) {
                        break;
                    }
                } catch (Exception e) {
                    break;
                }
            }

            if (!stack.isEmpty()) {
                List<CompletionItem> list = new ArrayList<>();
                for (int i = stack.size() - 1; i >= 0; i--) {
                    String s = stack.get(i);
                    list.add(new CompletionItem(s, "tag"));
                }
                return list;
            }

        } catch (XmlPullParserException | IOException e) {
            return null;
        }
        return null;
    }
}
