package com.tyron.code.editor;

import android.os.Build;
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

import com.tyron.code.R;
import com.tyron.code.editor.language.LanguageManager;
import com.tyron.code.parser.FileManager;

import java.io.File;

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
        mRoot = (LinearLayout) inflater.inflate(R.layout.code_editor_fragment, container, false);

        mContent = mRoot.findViewById(R.id.content);
        
        mEditor = new CodeEditor(requireActivity());
        mEditor.setEditorLanguage(LanguageManager.getInstance().get(mEditor, mCurrentFile));
        mEditor.setColorScheme(new SchemeDarcula());
        mEditor.setOverScrollEnabled(false);
        mEditor.setTextSize(12);
        mEditor.setCurrentFile(mCurrentFile);
        mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mEditor.setTypefaceText(ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		}
        mContent.addView(mEditor, new FrameLayout.LayoutParams(-1, -1));
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (mCurrentFile.exists()) {
           mEditor.setText(FileManager.readFile(mCurrentFile));
        }
    }
    
    public void save() {
        FileManager.getInstance().save(mCurrentFile, mEditor.getText().toString());
    }
}
