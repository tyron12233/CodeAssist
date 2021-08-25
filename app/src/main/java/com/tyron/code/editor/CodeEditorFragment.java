package com.tyron.code.editor;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.res.ResourcesCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.android.tools.r8.D8;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.sun.source.tree.CompilationUnitTree;
import com.tyron.code.ParseTask;
import com.tyron.code.R;
import com.tyron.code.compiler.JavaCompiler;
import com.tyron.code.editor.language.java.JavaLanguage;
import com.tyron.code.editor.log.LogViewModel;
import com.tyron.code.model.TextEdit;
import com.tyron.code.parser.JavaParser;
import com.tyron.code.rewrite.AddImport;
import dalvik.system.DexClassLoader;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.schemes.SchemeDarcula;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import com.tyron.code.parser.FileManager;
import com.tyron.code.ApplicationLoader;
import java.util.ArrayList;
import java.util.List;
import android.os.Build;
import com.tyron.code.editor.language.LanguageManager;

public class CodeEditorFragment extends Fragment {

    private LinearLayout mRoot;
    private LinearLayout mContent;
    
    private LogViewModel logViewModel;
    
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
        
        mCurrentFile = new File(getArguments().getString("path"));
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
        mEditor.setTypefaceText(ResourcesCompat.getFont(getContext(), R.font.jetbrains_mono_regular));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		}
        mContent.addView(mEditor, new FrameLayout.LayoutParams(-1, -1));
        
        logViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        return mRoot;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (mCurrentFile.exists()) {
           mEditor.setText(FileManager.readFile(mCurrentFile));
        }
    }
    
    public void save() {
        FileManager.getInstance().save(mCurrentFile, mEditor.getText().toString());
    }
}
