package com.tyron.code.editor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.tyron.code.R;
import com.tyron.code.editor.language.java.JavaLanguage;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.schemes.SchemeDarcula;
import android.view.MenuInflater;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.res.ResourcesCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.appbar.AppBarLayout;
import android.view.ViewGroup.MarginLayoutParams;
import com.tyron.code.editor.log.LogViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import com.tyron.code.compiler.JavaCompiler;
import com.android.tools.r8.D8;
import java.util.List;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import androidx.activity.OnBackPressedCallback;

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
        mToolbar.setTitle("Test.java");
        mToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem p1) {
                    if (p1.getItemId() == R.id.action_run) {
                        logViewModel.clear(LogViewModel.BUILD_LOG);
                        mEditor.hideAutoCompleteWindow();
                        mEditor.hideSoftInput();
                        compile();
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
                    D8.main(new String[]{"--output", requireContext().getCacheDir().getAbsolutePath(), "--lib", requireContext().getFilesDir() + "/rt.jar", requireContext().getCacheDir() + "/Test.class"});
                    
                    DexClassLoader loader = new DexClassLoader(requireContext().getCacheDir() + "/classes.dex",
                    requireContext().getCacheDir().getAbsolutePath(), null, ClassLoader.getSystemClassLoader());
                    
                    Class<?> test = loader.loadClass("Test");
                    Method method = test.getMethod("main", Context.class);
                    method.invoke(test.newInstance(), requireContext());
                    
                } catch (Throwable e) {
                    logViewModel.d(LogViewModel.BUILD_LOG, "[D8] FAILED:" + e.getMessage());
                }
            }
        });
    }
}
