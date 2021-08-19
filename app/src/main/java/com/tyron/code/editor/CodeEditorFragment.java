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

public class CodeEditorFragment extends Fragment {

    private static final String README = "" +
    "import android.app.AlertDialog;\n" +
    "import android.content.Context;\n" +
    "\n" +
    "/**\n" +
    "  * Thank you for testing CodeAssist\n" +
    "  * This is a very early version used to show that code completion can\n" +
    "  * be done on android without OpenJDK.\n" +
    "  * \n" +
    "  * This version may contain critical bugs.\n" +
    "  * @author tyron\n" +
    "  */\n" +
    "public class Test {\n" +
    "\n" +
    "    // Here you are given the editor's Activity context\n" +
    "    // Any code you run here will be run as if it was part of the app.\n" +
    "    public static void main(Context context) {\n" +
    "        AlertDialog dialog = new AlertDialog.Builder(context)\n" +
    "                .setTitle(\"Test\")\n" +
    "                .setMessage(\"This is a test message\")\n" +
    "                .setPositiveButton(\"CLOSE\", null)\n" +
    "                .create();\n" +
    "        dialog.show();\n" +
    "    }\n" + 
    "}";

    private LinearLayout mRoot;
    private AppBarLayout mAppBar;
    private LinearLayout mContent;
    private FrameLayout mBottomContainer;
    private BottomSheetBehavior mBehavior;
    
    private LogViewModel logViewModel;
    
    private Toolbar mToolbar;
    private CodeEditor mEditor;
    
    private File mCurrentFile = new File(ApplicationLoader.applicationContext.getFilesDir() + "/Test.java");

    public static CodeEditorFragment newInstance() {
        CodeEditorFragment fragment = new CodeEditorFragment();
        return fragment;
    }
    
    OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
             if (mBehavior != null) {
                 mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
             }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = (LinearLayout) inflater.inflate(R.layout.code_editor_fragment, container, false);

        mContent = mRoot.findViewById(R.id.content);
        mAppBar = mRoot.findViewById(R.id.appbar_layout);
        mBottomContainer = mRoot.findViewById(R.id.persistent_sheet);
        
        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.inflateMenu(R.menu.code_editor_menu);

        mEditor = new CodeEditor(requireActivity());
        mEditor.setEditorLanguage(new JavaLanguage(mEditor));
        mEditor.setColorScheme(new SchemeDarcula());
        mEditor.setOverScrollEnabled(false);
        mEditor.setTextSize(12);
        mEditor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        mEditor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS | EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mEditor.setTypefaceText(ResourcesCompat.getFont(getContext(), R.font.JetBrainsMonoRegular));
        mContent.addView(mEditor, new FrameLayout.LayoutParams(-1, -1));
        
        logViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
        return mRoot;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mEditor.setText(README);
        
        final BottomEditorFragment bottomEditorFragment = BottomEditorFragment.newInstance();
        mBehavior = BottomSheetBehavior.from(mBottomContainer);
        mBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(View p1, int state) {
                    switch (state) {
                        case BottomSheetBehavior.STATE_COLLAPSED:
                            mEditor.setEnabled(true);
                            onBackPressedCallback.setEnabled(false);
                            break;
                        case BottomSheetBehavior.STATE_EXPANDED:
                            mEditor.setEnabled(false);
                            onBackPressedCallback.setEnabled(true);
                    }
                }

                @Override
                public void onSlide(View bottomSheet, float slideOffset) {
                    bottomEditorFragment.setOffset(slideOffset);
                }              
            });
        
        //TODO: adjust to file name
        mToolbar.setTitle(mCurrentFile.getName());
        mToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem p1) {
                    if (p1.getItemId() == R.id.action_run) {
                         FileManager.getInstance().save(mCurrentFile, mEditor.getText().toString());
                        logViewModel.clear(LogViewModel.BUILD_LOG);
                        mEditor.hideAutoCompleteWindow();
                        mEditor.hideSoftInput();
                        compile();
                        
                     //   JavaParser parser = new JavaParser(logViewModel);
                    //    CompilationUnitTree unit = parser.parse(mEditor.getText().toString(), mEditor.getCursor().getLeft());
                    //    ParseTask task = new ParseTask(parser.getTask(), unit);
                    //    Map<File, TextEdit> edit = new AddImport(mCurrentFile, "com.tyron.code.Test").getText(task);
                        
                     //   TextEdit textEdit = edit.values().iterator().next();
                      //  mEditor.getText().insert(textEdit.start.line, textEdit.start.column, textEdit.edit);
                        
                       // parser.readImports(mCurrentFile).toString();
                    }
                    return true;
                }         
            });
 
        
        // Display the persistent fragment
        getChildFragmentManager().beginTransaction()
                .replace(R.id.persistent_sheet, bottomEditorFragment)
                .commit();
    }
    
    private void compile() {
        JavaCompiler compiler = new JavaCompiler(logViewModel);
        compiler.compile(mEditor.getText().toString(), new JavaCompiler.OnCompleteListener() {
            @Override
            public void onComplete(boolean success) {
                
                if (!success) {
                    mBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    return;
                }
                
                logViewModel.d(LogViewModel.BUILD_LOG, "[D8] Running");           
                try {                  
                    List<String> args = new ArrayList<>();
                    args.add("--lib");
                    args.add(FileManager.getInstance().getAndroidJar().getAbsolutePath());
                    args.add("--output");
                    args.add(ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath());
                    for (File file : ApplicationLoader.applicationContext.getCacheDir().listFiles()) {
                        if (file.getName().endsWith(".class")) {
                            args.add(file.getAbsolutePath());
                        }
                    }
                    D8.main(args.toArray(new String[0]));
                    DexClassLoader loader = new DexClassLoader(requireContext().getCacheDir() + "/classes.dex",
                    requireContext().getCacheDir().getAbsolutePath(), requireContext().getCacheDir().getAbsolutePath(), ClassLoader.getSystemClassLoader());
                    
                    Class<?> test = loader.loadClass("Test");
                    Method method = test.getMethod("main", Context.class);
                    method.invoke(test.newInstance(), requireContext());
                    
                } catch (InvocationTargetException e) {
                    logViewModel.d(LogViewModel.BUILD_LOG, "Exception thrown in main: " + e.getCause().getMessage());
                    mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                } catch (Throwable e) {
                    logViewModel.d(LogViewModel.BUILD_LOG, "[D8] FAILED:" + e.getMessage());
                }
            }
        });
    }
}
