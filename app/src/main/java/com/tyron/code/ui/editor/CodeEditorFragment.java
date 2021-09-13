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
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.action.CodeActionProvider;
import com.tyron.code.completion.CompileTask;
import com.tyron.code.completion.JavaCompilerService;
import com.tyron.code.completion.SourceFileObject;
import com.tyron.code.completion.provider.CompletionEngine;
import com.tyron.code.model.CodeAction;
import com.tyron.code.model.CodeActionList;
import com.tyron.code.model.Project;
import com.tyron.code.model.Range;
import com.tyron.code.model.TextEdit;
import com.tyron.code.parser.FileManager;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.editor.language.java.JavaAnalyzer;
import com.tyron.code.ui.editor.language.java.JavaLanguage;
import com.tyron.code.ui.editor.language.xml.LanguageXML;
import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.util.ProjectUtils;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import io.github.rosemoe.editor.interfaces.EditorEventListener;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.text.Content;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.schemes.SchemeDarcula;

@SuppressWarnings("FieldCanBeLocal")
public class CodeEditorFragment extends Fragment {

    private LinearLayout mRoot;
    private LinearLayout mContent;
    private CodeEditor mEditor;

    private EditorLanguage mLanguage;
    private File mCurrentFile = new File("");
    private MainViewModel mMainViewModel;

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
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

	@Override
	public void onPause() {
		super.onPause();

        mEditor.getTextActionPresenter().onExit();
        mEditor.hideAutoCompleteWindow();
//        if (mLanguage instanceof LanguageXML) {
//            Project project = FileManager.getInstance().getCurrentProject();
//            if (mCurrentFile != null && project != null && ProjectUtils.isResourceXMLFile(mCurrentFile)) {
//                File resourceFile = project.getRJavaFiles().get(project.getPackageName());
//                if (resourceFile != null && resourceFile.exists()) {
//                    mMainViewModel.setIndexing(true);
//                    mMainViewModel.setCurrentState("Indexing R.java");
//                    Executors.newSingleThreadExecutor().submit(() -> {
//                        JavaCompilerService service = CompletionEngine.getInstance().getCompiler();
//                        try (CompileTask task = service.compile(resourceFile.toPath())) {
//                           mMainViewModel.isIndexing().postValue(false);
//                           mMainViewModel.getCurrentState().postValue(null);
//                        }
//                    });
//                }
//            }
//        }
	}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        mRoot = (LinearLayout) inflater.inflate(R.layout.code_editor_fragment, container, false);
        mContent = mRoot.findViewById(R.id.content);
        
        mEditor = new CodeEditor(requireActivity());
        mEditor.setEditorLanguage(mLanguage = LanguageManager.getInstance().get(mEditor, mCurrentFile));
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
        mEditor.setOnLongPressListener((start, end, event) -> {
            if (mLanguage instanceof JavaLanguage) {
                final Path current = mEditor.getCurrentFile().toPath();
                List<CodeActionList> actions = new CodeActionProvider(CompletionEngine.getInstance().getCompiler())
                        .codeActionsForCursor(current, mEditor.getCursor().getLeft());

                mEditor.setOnCreateContextMenuListener((menu, view1, info) -> {
                    for (final CodeActionList action : actions) {
                        if (action.getActions().isEmpty()) {
                            continue;
                        }
                        menu.add(action.getTitle()).setOnMenuItemClickListener(menuItem -> {
                            FileManager.writeFile(mEditor.getCurrentFile(), mEditor.getText().toString());
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(action.getTitle())
                                    .setItems(action.getActions().stream().map(CodeAction::getTitle).toArray(String[]:: new), ((dialogInterface, i) -> {
                                        CodeAction codeAction = action.getActions().get(i);
                                        Map<Path, List<TextEdit>> rewrites = codeAction.getEdits();
                                        List<TextEdit> edits = rewrites.values().iterator().next();
                                        for (TextEdit edit : edits) {
                                            Range range = edit.range;
                                            if (range.start.equals(range.end)) {
                                                mEditor.getText().insert(range.start.line, range.start.column, edit.newText);
                                            } else {
                                                mEditor.getText().replace(range.start.line, range.start.column, range.end.line, range.end.column, edit.newText);
                                            }
                                        }
                                    })).show();
                            return true;
                        });
                    }
                });
                mEditor.showContextMenu(event.getX(), event.getY());
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
