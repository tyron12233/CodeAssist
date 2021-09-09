package com.tyron.code.ui.editor;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.tyron.code.R;
import com.tyron.code.parser.FileManager;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import java.io.File;

import io.github.rosemoe.editor.interfaces.EditorEventListener;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.schemes.SchemeDarcula;

@SuppressWarnings("FieldCanBeLocal")
public class CodeEditorFragment extends Fragment {

    private LinearLayout mRoot;
    private LinearLayout mContent;
    private CodeEditor mEditor;
    
    private File mCurrentFile = new File("");

    public static CodeEditorFragment newInstance(File file) {
        CodeEditorFragment fragment = new CodeEditorFragment();
        Bundle args = new Bundle();
        args.putString("path", file.getAbsolutePath());
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assert getArguments() != null;
        mCurrentFile = new File(getArguments().getString("path", ""));
    }

	@Override
	public void onPause() {
		super.onPause();
		
		mEditor.hideAutoCompleteWindow();
	}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        mRoot = (LinearLayout) inflater.inflate(R.layout.code_editor_fragment, container, false);
        mContent = mRoot.findViewById(R.id.content);
        
        mEditor = new CodeEditor(requireActivity());
        mEditor.setEditorLanguage(LanguageManager.getInstance().get(mEditor, mCurrentFile));
        mEditor.setColorScheme(new SchemeDarcula());
        mEditor.setOverScrollEnabled(false);
        mEditor.setTextSize(Integer.parseInt(preferences.getString("font_size", "12")));
        mEditor.setCurrentFile(mCurrentFile);
        mEditor.setTextActionMode(CodeEditor.TextActionMode.POPUP_WINDOW);
        mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mEditor.setTypefaceText(ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
        mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        mContent.addView(mEditor, new FrameLayout.LayoutParams(-1, -1));
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (mCurrentFile.exists()) {
           mEditor.setText(FileManager.readFile(mCurrentFile));
        }

        mEditor.setEventListener(new EditorEventListener() {
            @Override
            public boolean onRequestFormat(CodeEditor editor, boolean async) {
                return false;
            }

            @Override
            public boolean onFormatFail(CodeEditor editor, Throwable cause) {
                return false;
            }

            @Override
            public void onFormatSucceed(CodeEditor editor) {

            }

            @Override
            public void onNewTextSet(CodeEditor editor) {

            }

            @Override
            public void afterDelete(CodeEditor editor, CharSequence content, int startLine, int startColumn, int endLine, int endColumn, CharSequence deletedContent) {

            }

            @Override
            public void afterInsert(CodeEditor editor, CharSequence content, int startLine, int startColumn, int endLine, int endColumn, CharSequence insertedContent) {

            }

            @Override
            public void beforeReplace(CodeEditor editor, CharSequence content) {

            }
        });
    }
    
    public void save() {
        if(mCurrentFile.exists()) {
            FileManager.getInstance().save(mCurrentFile, mEditor.getText().toString());
        }
    }

    public void setCursorPosition(int line, int column) {
        if (mEditor != null) {
            mEditor.getCursor()
                    .set(line, column);
        }
    }

    public void performShortcut(ShortcutItem item) {
        for (ShortcutAction action : item.actions) {
            if (action.isApplicable(item.kind)) {
                action.apply(mEditor, item);
            }
        }
    }

    public void format() {
        if (mEditor != null) {
            mEditor.formatCodeAsync();
        }
    }
}
