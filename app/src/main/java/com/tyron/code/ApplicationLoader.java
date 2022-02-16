package com.tyron.code;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.developer.crashx.config.CrashConfig;
import com.tyron.actions.ActionManager;
import com.tyron.builder.BuildModule;
import com.tyron.code.ui.editor.action.CloseAllEditorAction;
import com.tyron.code.ui.editor.action.CloseFileEditorAction;
import com.tyron.code.ui.editor.action.CloseOtherEditorAction;
import com.tyron.code.ui.editor.action.DiagnosticInfoAction;
import com.tyron.code.ui.editor.action.PreviewLayoutAction;
import com.tyron.code.ui.editor.action.text.TextActionGroup;
import com.tyron.code.ui.file.action.NewFileActionGroup;
import com.tyron.code.ui.file.action.file.DeleteFileAction;
import com.tyron.code.ui.main.action.compile.CompileActionGroup;
import com.tyron.code.ui.main.action.debug.DebugActionGroup;
import com.tyron.code.ui.main.action.other.FormatAction;
import com.tyron.code.ui.main.action.other.OpenSettingsAction;
import com.tyron.code.ui.main.action.project.ProjectActionGroup;
import com.tyron.code.ui.settings.ApplicationSettingsFragment;
import com.tyron.common.ApplicationProvider;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.xml.XmlCompletionModule;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.providers.AndroidManifestCompletionProvider;
import com.tyron.completion.xml.providers.LayoutXmlCompletionProvider;
import com.tyron.editor.selection.ExpandSelectionProvider;
import com.tyron.kotlin_completion.KotlinCompletionModule;
import com.tyron.language.fileTypes.FileTypeManager;
import com.tyron.language.java.JavaFileType;
import com.tyron.language.java.JavaLanguage;
import com.tyron.language.xml.XmlFileType;
import com.tyron.language.xml.XmlLanguage;
import com.tyron.selection.java.JavaExpandSelectionProvider;

public class ApplicationLoader extends Application {
    
    public static Context applicationContext;
    public static Handler applicationHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        setupTheme();
        applicationContext = this;

        ApplicationProvider.initialize(applicationContext);

        CompletionModule.initialize(applicationContext);
        XmlCompletionModule.initialize(applicationContext);
        BuildModule.initialize(applicationContext);

        CrashConfig.Builder.create()
                .backgroundMode(CrashConfig.BACKGROUND_MODE_SHOW_CUSTOM)
                .enabled(true)
                .showErrorDetails(true)
                .showRestartButton(true)
                .logErrorOnRestart(true)
                .trackActivities(true)
                .apply();

        runStartup();
    }

    private void runStartup() {
        StartupManager startupManager = new StartupManager();
        startupManager.addStartupActivity(() -> {
            FileTypeManager manager = FileTypeManager.getInstance();
            manager.registerFileType(JavaFileType.INSTANCE);
            manager.registerFileType(XmlFileType.INSTANCE);
        });
        startupManager.addStartupActivity(() -> {
            ExpandSelectionProvider.registerProvider(JavaLanguage.INSTANCE,
                                                     new JavaExpandSelectionProvider());
        });
        startupManager.addStartupActivity(() -> {
            CompletionEngine engine = CompletionEngine.getInstance();
            CompilerService index = CompilerService.getInstance();
            if (index.isEmpty()) {
                index.registerIndexProvider(JavaCompilerProvider.KEY, new JavaCompilerProvider());
                index.registerIndexProvider(XmlIndexProvider.KEY, new XmlIndexProvider());
            }
        });
        startupManager.addStartupActivity(() -> {
            CompletionProvider.registerCompletionProvider(JavaLanguage.INSTANCE,
                                                          new JavaCompletionProvider());
            CompletionProvider.registerCompletionProvider(XmlLanguage.INSTANCE,
                                                          new LayoutXmlCompletionProvider());
            CompletionProvider.registerCompletionProvider(XmlLanguage.INSTANCE,
                                                          new AndroidManifestCompletionProvider());
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
            manager.registerAction(TextActionGroup.ID, new TextActionGroup());
            manager.registerAction(DiagnosticInfoAction.ID, new DiagnosticInfoAction());

            // file manager actions
            manager.registerAction(NewFileActionGroup.ID, new NewFileActionGroup());
            manager.registerAction(DeleteFileAction.ID, new DeleteFileAction());

            // java actions
            CompletionModule.registerActions(manager);

            // xml actions
            XmlCompletionModule.registerActions(manager);

            // kotlin actions
            KotlinCompletionModule.registerActions(manager);
        });
        startupManager.startup();
    }

    private void setupTheme() {
        ApplicationSettingsFragment.ThemeProvider provider = new ApplicationSettingsFragment.ThemeProvider(this);
        int theme = provider.getThemeFromPreferences();
        AppCompatDelegate.setDefaultNightMode(theme);
    }

    public static SharedPreferences getDefaultPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(applicationContext);
    }

    public static void showToast(String message) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
                .show();
    }

    @VisibleForTesting
    public static void setApplicationContext(Context context) {
        applicationContext = context;
    }
}
