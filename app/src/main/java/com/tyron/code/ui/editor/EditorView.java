package com.tyron.code.ui.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.tyron.code.ui.legacyEditor.CodeAssistCompletionAdapter;
import com.tyron.editor.impl.EditorImpl;

import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

@SuppressLint("ViewConstructor")
public class EditorView extends FrameLayout {

    private final EditorImpl editor;
    private final Content content;
    private final CodeEditor soraEditorView;
    public EditorView(@NonNull Context context, EditorViewModel.InternalEditorState editorState) {
        super(context);
        this.editor = (EditorImpl) editorState.getEditor();
        this.content = editorState.getEditorContent();
        
        this.soraEditorView = new CodeEditor(context);
        soraEditorView.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter(editorState.getEditor()));
        soraEditorView.setText(editorState.getEditorContent());
        soraEditorView.setEditorLanguage(editorState.getSoraLanguage());
        addView(soraEditorView);
    }

    
    
}
