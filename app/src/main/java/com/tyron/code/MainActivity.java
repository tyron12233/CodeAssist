package com.tyron.code;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.actions.ActionManager;
import com.tyron.code.ui.editor.action.CloseAllEditorAction;
import com.tyron.code.ui.editor.action.CloseFileEditorAction;
import com.tyron.code.ui.editor.action.CloseOtherEditorAction;
import com.tyron.code.ui.editor.action.DiagnosticInfoAction;
import com.tyron.code.ui.editor.action.PreviewLayoutAction;
import com.tyron.code.ui.file.action.NewFileActionGroup;
import com.tyron.code.ui.file.action.file.DeleteFileAction;
import com.tyron.code.ui.main.action.compile.CompileActionGroup;
import com.tyron.code.ui.main.action.debug.DebugActionGroup;
import com.tyron.code.ui.main.action.other.FormatAction;
import com.tyron.code.ui.main.action.other.OpenSettingsAction;
import com.tyron.code.ui.main.action.project.ProjectActionGroup;
import com.tyron.code.ui.project.ProjectManagerFragment;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.xml.XmlCompletionModule;
import com.tyron.completion.xml.providers.AndroidManifestCompletionProvider;
import com.tyron.completion.xml.providers.LayoutXmlCompletionProvider;
import com.tyron.completion.xml.XmlIndexProvider;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        StartupManager startupManager = new StartupManager();
        startupManager.addStartupActivity(() -> {
            CompletionEngine engine = CompletionEngine.getInstance();
            CompilerService index = CompilerService.getInstance();
            if (index.isEmpty()) {
                index.registerIndexProvider(JavaCompilerProvider.KEY, new JavaCompilerProvider());
                index.registerIndexProvider(XmlIndexProvider.KEY, new XmlIndexProvider());
                engine.registerCompletionProvider(new JavaCompletionProvider());
                engine.registerCompletionProvider(new LayoutXmlCompletionProvider());
                engine.registerCompletionProvider(new AndroidManifestCompletionProvider());
            }
        });
        startupManager.addStartupActivity(() -> {
            ActionManager manager = ActionManager.getInstance();
            // main toolbar actions
            manager.registerAction(CompileActionGroup.ID, new CompileActionGroup());
            manager.registerAction(ProjectActionGroup.ID, new ProjectActionGroup());
            manager.registerAction(PreviewLayoutAction.ID, new PreviewLayoutAction());
            manager.registerAction(OpenSettingsAction.ID, new OpenSettingsAction());
            manager.registerAction(FormatAction.ID, new FormatAction());
            manager.registerAction(DebugActionGroup.ID, new DebugActionGroup());

            // editor tab actions
            manager.registerAction(CloseFileEditorAction.ID, new CloseFileEditorAction());
            manager.registerAction(CloseOtherEditorAction.ID, new CloseOtherEditorAction());
            manager.registerAction(CloseAllEditorAction.ID, new CloseAllEditorAction());

            // editor actions
            manager.registerAction(DiagnosticInfoAction.ID, new DiagnosticInfoAction());

            // file manager actions
            manager.registerAction(NewFileActionGroup.ID, new NewFileActionGroup());
            manager.registerAction(DeleteFileAction.ID, new DeleteFileAction());

            // java actions
            CompletionModule.registerActions(manager);

            // xml actions
            XmlCompletionModule.registerActions(manager);
        });
        startupManager.startup();

        if (getSupportFragmentManager().findFragmentByTag(ProjectManagerFragment.TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container,
                            new ProjectManagerFragment(),
                            ProjectManagerFragment.TAG)
                    .commit();
        }
    }
	
	@Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
