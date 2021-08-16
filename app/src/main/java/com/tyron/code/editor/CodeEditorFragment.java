package com.tyron.code.editor;
import android.app.Fragment;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.View;
import android.os.Bundle;
import android.widget.FrameLayout;
import io.github.rosemoe.editor.widget.CodeEditor;
import com.tyron.code.editor.language.java.JavaLanguage;
import io.github.rosemoe.editor.widget.schemes.SchemeDarcula;

public class CodeEditorFragment extends Fragment {
    
    private FrameLayout mRoot;
    private CodeEditor mEditor;
    
    public static CodeEditorFragment newInstance() {
        CodeEditorFragment fragment = new CodeEditorFragment();
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = new FrameLayout(getContext());
        
        mEditor = new CodeEditor(getContext());
        mEditor.setEditorLanguage(new JavaLanguage(mEditor));
        mEditor.setColorScheme(new SchemeDarcula());
        
        mRoot.addView(mEditor, new FrameLayout.LayoutParams(-1, -1));
        
        return mRoot;
    }
}
