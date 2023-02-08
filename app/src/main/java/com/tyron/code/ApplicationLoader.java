package com.tyron.code;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.developer.crashx.config.CrashConfig;
import com.tyron.actions.ActionManager;
import com.tyron.builder.BuildModule;
import com.tyron.code.event.EventManager;
import com.tyron.code.highlighter.attributes.CodeAssistTextAttributes;
import com.tyron.code.highlighter.attributes.CodeAssistTextAttributesProvider;
import com.tyron.code.highlighter.attributes.TextAttributesKeyUtils;
import com.tyron.code.service.GradleDaemonService;
import com.tyron.code.ui.editor.action.CloseAllEditorAction;
import com.tyron.code.ui.editor.action.CloseFileEditorAction;
import com.tyron.code.ui.editor.action.CloseOtherEditorAction;
import com.tyron.code.ui.editor.action.DiagnosticInfoAction;
import com.tyron.code.ui.editor.action.PreviewLayoutAction;
import com.tyron.code.ui.editor.action.text.TextActionGroup;
import com.tyron.code.ui.file.action.ImportFileActionGroup;
import com.tyron.code.ui.file.action.NewFileActionGroup;
import com.tyron.code.ui.file.action.file.DeleteFileAction;
import com.tyron.code.ui.main.action.compile.CompileActionGroup;
import com.tyron.code.ui.main.action.debug.DebugActionGroup;
import com.tyron.code.ui.main.action.other.FormatAction;
import com.tyron.code.ui.main.action.other.OpenSettingsAction;
import com.tyron.code.ui.main.action.project.ProjectActionGroup;
import com.tyron.code.ui.settings.ApplicationSettingsFragment;
import com.tyron.common.ApplicationProvider;
import com.tyron.completion.CompletionContributor;
import com.tyron.completion.CompletionContributorEP;
import com.tyron.completion.CompletionService;
import com.tyron.completion.impl.CompletionServiceImpl;
import com.tyron.completion.legacy.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.psi.codeInsight.completion.JavaCompletionContributor;
import com.tyron.completion.xml.XmlCompletionModule;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.editor.selection.ExpandSelectionProvider;
import com.tyron.kotlin_completion.KotlinCompletionModule;
import com.tyron.language.fileTypes.FileTypeManager;
import com.tyron.language.java.JavaFileType;
import com.tyron.language.java.JavaLanguage;
import com.tyron.language.xml.XmlFileType;
import com.tyron.language.xml.XmlLanguage;
import com.tyron.selection.java.JavaExpandSelectionProvider;
import com.tyron.selection.xml.XmlExpandSelectionProvider;

import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.jetbrains.concurrency.CancellablePromise;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.jvm.compiler.IdeaStandaloneExecutionSetup;
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.MetaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.AppUIExecutor;
import org.jetbrains.kotlin.com.intellij.openapi.application.AsyncExecutionService;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.application.NonBlockingReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuard;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuardImpl;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.DefaultPluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem;
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider;
import org.jetbrains.kotlin.com.intellij.psi.impl.JavaClassSupersImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy;
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider;
import org.jetbrains.kotlin.com.intellij.psi.util.JavaClassSupers;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class ApplicationLoader extends Application {

    private static ApplicationLoader sInstance;
    public static Context applicationContext;

    public static ApplicationLoader getInstance() {
        return sInstance;
    }

    private EventManager mEventManager;
    private JavaCoreApplicationEnvironment coreApplicationEnvironment;

    private final Disposable disposable = Disposer.newDisposable("Application Environment");

    @Override
    public void onCreate() {
        Timer timer = Time.startTimer();

        UtilKt.setIdeaIoUseFallback();
        IdeaStandaloneExecutionSetup.INSTANCE.doSetup();

        coreApplicationEnvironment = new JavaCoreApplicationEnvironment(disposable);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(DocumentWriteAccessGuard.EP_NAME,
                DocumentWriteAccessGuard.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(PsiAugmentProvider.EP_NAME,
                PsiAugmentProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(JavaModuleSystem.EP_NAME,
                JavaModuleSystem.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(SmartPointerAnchorProvider.EP_NAME,
                SmartPointerAnchorProvider.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(ClsCustomNavigationPolicy.EP_NAME,
                ClsCustomNavigationPolicy.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(FileDocumentManagerListener.EP_NAME,
                FileDocumentManagerListener.class);
        CoreApplicationEnvironment.registerApplicationExtensionPoint(MetaLanguage.EP_NAME,
                MetaLanguage.class);
        coreApplicationEnvironment.registerApplicationService(AsyncExecutionService.class, new AsyncExecutionService() {
            @Override
            protected @NonNull AppUIExecutor createWriteThreadExecutor(@NonNull ModalityState modalityState) {
                return new AppUIExecutor() {
                    @Override
                    public @NonNull AppUIExecutor later() {
                        return this;
                    }

                    @Override
                    public @NonNull AppUIExecutor expireWith(@NonNull Disposable disposable) {
                        return this;
                    }

                    @Override
                    public CancellablePromise<?> submit(@NonNull Runnable runnable) {
                        CompletableFuture<?> future = CompletableFuture.runAsync(runnable);
                        return new CancellablePromise<>() {
                            @Override
                            public boolean cancel(boolean mayInterruptIfRunning) {
                                return future.cancel(mayInterruptIfRunning);
                            }

                            @Override
                            public boolean isCancelled() {
                                return future.isCancelled();
                            }

                            @Override
                            public boolean isDone() {
                                return future.isDone();
                            }

                            @Override
                            public Object get() throws ExecutionException, InterruptedException {
                                return future.get();
                            }

                            @Override
                            public Object get(long timeout,
                                              TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
                                return future.get(timeout, unit);
                            }
                        };
                    }
                };
            }

            @Override
            protected @NonNull <T> NonBlockingReadAction<T> buildNonBlockingReadAction(
                    @NonNull Callable<T> callable) {

                return new NonBlockingReadAction<T>() {

                    CompletableFuture<T> future = new CompletableFuture<>();

                    @Override
                    public @NonNull NonBlockingReadAction<T> expireWhen(@NonNull BooleanSupplier booleanSupplier) {
                        return this;
                    }

                    @Override
                    public @NonNull NonBlockingReadAction<T> finishOnUiThread(@NonNull ModalityState modalityState,
                                                                              @NonNull Consumer<? super T> consumer) {
                        future.whenComplete((t, throwable) -> consumer.accept(t));
                        return this;
                    }

                    @Override
                    public @NonNull NonBlockingReadAction<T> coalesceBy(Object... objects) {
                        return this;
                    }

                    @Override
                    public @NonNull CancellablePromise<T> submit(@NonNull Executor executor) {
                        return new CancellablePromise<>() {
                            @Override
                            public boolean cancel(boolean mayInterruptIfRunning) {
                                return future.cancel(mayInterruptIfRunning);
                            }

                            @Override
                            public boolean isCancelled() {
                                return future.isCancelled();
                            }

                            @Override
                            public boolean isDone() {
                                return future.isDone();
                            }

                            @Override
                            public T get() throws ExecutionException, InterruptedException {
                                return future.get();
                            }

                            @Override
                            public T get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
                                return future.get(timeout, unit);
                            }
                        };
                    }
                };
            }
        });
        coreApplicationEnvironment.registerApplicationService(CompletionService.class,
                new CompletionServiceImpl());
        coreApplicationEnvironment.registerApplicationService(TransactionGuard.class,
                new TransactionGuardImpl());
        coreApplicationEnvironment.registerApplicationService(JavaClassSupers.class,
                new JavaClassSupersImpl());
        coreApplicationEnvironment.registerApplicationService(CodeAssistTextAttributesProvider.class,
                new CodeAssistTextAttributesProvider() {
                    public CodeAssistTextAttributes getDefaultAttributes(TextAttributesKey textAttributesKey) {
                        String name = TextAttributesKeyUtils.getExternalName(textAttributesKey);

                        switch (Objects.requireNonNull(name)) {
                            case "DEFAULT_KEYWORD":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.KEYWORD,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_PARAMETER":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.IDENTIFIER_NAME,
                                        0,
                                        0,
                                        null);
                            case "DEFAULT_STRING":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.LITERAL,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_LINE_COMMENT":
                            case "DEFAULT_BLOCK_COMMENT":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.COMMENT,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_OPERATION_SIGN":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.OPERATOR,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_INVALID_STRING_ESCAPE":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.PROBLEM_ERROR,
                                        Color.RED,
                                        0,
                                        null);
                            case "DEFAULT_FUNCTION_DECLARATION":
                                return new CodeAssistTextAttributes(Color.TRANSPARENT,
                                        EditorColorScheme.FUNCTION_NAME,
                                        Color.RED,
                                        0,
                                        null);
                        }


                        TextAttributesKey fallbackAttributeKey =
                                TextAttributesKeyUtils.getFallbackAttributeKey(textAttributesKey);
                        if (fallbackAttributeKey != null) {
                            return TextAttributesKeyUtils.getDefaultAttributes(fallbackAttributeKey);
                        }

                        return CodeAssistTextAttributes.DEFAULT;
                    }

                });


        // language specific stuff
        coreApplicationEnvironment.addExplicitExtension(CompletionContributor.INSTANCE,
                org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage.INSTANCE,
                new JavaCompletionContributor());


        super.onCreate();
        System.out.println("onCreate took " + timer.getElapsed());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Lsun/misc/Unsafe");
        }

        setupTheme();

        mEventManager = new EventManager();

        sInstance = this;
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

        File userDir = new File(getFilesDir(), "user_dir");
        System.setProperty("codeassist.user.dir", userDir.getAbsolutePath());
    }

    public JavaCoreApplicationEnvironment getCoreApplicationEnvironment() {
        return coreApplicationEnvironment;
    }

    /**
     * Can be used to communicate within the application globally
     *
     * @return the EventManager
     */
    @NonNull
    public EventManager getEventManager() {
        return mEventManager;
    }

    private void setupTheme() {
        ApplicationSettingsFragment.ThemeProvider provider =
                new ApplicationSettingsFragment.ThemeProvider(this);
        int theme = provider.getThemeFromPreferences();
        AppCompatDelegate.setDefaultNightMode(theme);
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
            ExpandSelectionProvider.registerProvider(XmlLanguage.INSTANCE,
                    new XmlExpandSelectionProvider());
        });
        startupManager.addStartupActivity(() -> {
            CompilerService index = CompilerService.getInstance();
            if (index.isEmpty()) {
                index.registerIndexProvider(JavaCompilerProvider.KEY, new JavaCompilerProvider());
                index.registerIndexProvider(XmlIndexProvider.KEY, new XmlIndexProvider());
            }
        });
        startupManager.addStartupActivity(() -> {
            CompletionProvider.registerCompletionProvider(JavaLanguage.INSTANCE,
                    new JavaCompletionProvider());
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                manager.registerAction(ImportFileActionGroup.ID, new ImportFileActionGroup());
            }

            // java actions
            CompletionModule.registerActions(manager);

            // xml actions
            XmlCompletionModule.registerActions(manager);

            // kotlin actions
            KotlinCompletionModule.registerActions(manager);
        });
        startupManager.startup();
    }

    public static SharedPreferences getDefaultPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(applicationContext);
    }

    public static void showToast(String message) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show();
    }

    @VisibleForTesting
    public static void setApplicationContext(Context context) {
        applicationContext = context;
    }

    /**
     * Starts a new gradle daemon on a separate process.
     * <p>
     * Accessed reflectively via {@link org.gradle.launcher.daemon.client.DefaultDaemonStarter}
     */
    @Keep
    private static void startDaemonProcess(File dir) throws IOException {
        assert applicationContext != null;


        Intent intent = new Intent(applicationContext, GradleDaemonService.class);
        intent.putExtra("dir", dir.toString());

        applicationContext.startService(intent);
    }
}