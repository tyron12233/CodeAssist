package com.tyron.code.language.xml;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.BuildConfig;
import com.tyron.code.analyzer.DiagnosticTextmateAnalyzer;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.ProjectUtils;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.task.InjectResourcesTask;
import com.tyron.editor.Editor;
import com.tyron.viewbinding.task.InjectViewBindingTask;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import kotlin.Unit;

public class XMLAnalyzer extends DiagnosticTextmateAnalyzer {

    private boolean mAnalyzerEnabled = false;

    private static final Debouncer sDebouncer = new Debouncer(Duration.ofMillis(900L), Executors.newScheduledThreadPool(
            1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    ThreadGroup threadGroup = Looper.getMainLooper().getThread().getThreadGroup();
                    return new Thread(threadGroup, runnable, "XmlAnalyzer");
                }
            }));

    private final WeakReference<Editor> mEditorReference;

    public XMLAnalyzer(Editor editor,
                       String grammarName,
                       InputStream grammarIns,
                       Reader languageConfiguration,
                       IRawTheme theme) throws Exception {
        super(editor, grammarName, grammarIns, languageConfiguration, theme);

        mEditorReference = new WeakReference<>(editor);
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {
        Editor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }

        if (!mAnalyzerEnabled) {
            Project project = editor.getProject();
            if (project == null) {
                return;
            }

            ProgressManager.getInstance().runLater(() -> editor.setAnalyzing(true));

            sDebouncer.cancel();
            sDebouncer.schedule(cancel -> {
                AndroidModule mainModule = (AndroidModule) project.getMainModule();
                try {
                    InjectResourcesTask.inject(project, mainModule);
                    InjectViewBindingTask.inject(project, mainModule);
                    ProgressManager.getInstance().runLater(() -> editor.setAnalyzing(false), 300);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return Unit.INSTANCE;
            });
            return;
        }

        File currentFile = editor.getCurrentFile();
        if (currentFile == null) {
            return;
        }

        List<DiagnosticWrapper> diagnosticWrappers = new ArrayList<>();

        sDebouncer.cancel();
        sDebouncer.schedule(cancel -> {
            compile(currentFile, contents.toString(), new ILogger() {
                @Override
                public void info(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                @Override
                public void debug(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                @Override
                public void warning(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                @Override
                public void error(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                private void addMaybe(DiagnosticWrapper wrapper) {
                    if (currentFile.equals(wrapper.getSource())) {
                        diagnosticWrappers.add(wrapper);
                    }
                }
            });

            if (!cancel.invoke()) {
                ProgressManager.getInstance().runLater(() -> {
                    editor.setDiagnostics(
                            diagnosticWrappers.stream().filter(it -> it.getLineNumber() > 0)
                                    .collect(Collectors.toList()));
                });
            }
            return Unit.INSTANCE;
        });

    }

    private final Handler handler = new Handler();
    long delay = 1000L;
    long lastTime;

    private void compile(File file, String contents, ILogger logger) {
        boolean isResource = ProjectUtils.isResourceXMLFile(file);

        if (isResource) {
            Project project = ProjectManager.getInstance().getCurrentProject();
            if (project != null) {
                Module module = project.getModule(file);
                if (module instanceof AndroidModule) {
                    try {
                        doGenerate(project, (AndroidModule) module, file, contents, logger);
                    } catch (IOException | CompilationFailedException e) {
                        if (BuildConfig.DEBUG) {
                            Log.e("XMLAnalyzer", "Failed compiling", e);
                        }
                    }
                }
            }
        }
    }

    private void doGenerate(Project project,
                            AndroidModule module,
                            File file,
                            String contents,
                            ILogger logger) throws IOException, CompilationFailedException {
        if (!file.canWrite() || !file.canRead()) {
            return;
        }

        if (!module.getFileManager().isOpened(file)) {
            Log.e("XMLAnalyzer", "File is not yet opened!");
            return;
        }

        Optional<CharSequence> fileContent = module.getFileManager().getFileContent(file);
        if (!fileContent.isPresent()) {
            Log.e("XMLAnalyzer", "No snapshot for file found.");
            return;
        }

        contents = fileContent.get().toString();
        FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
        IncrementalAapt2Task task = new IncrementalAapt2Task(project, module, logger, false);

        try {
            task.prepare(BuildType.DEBUG);
            task.run();
        } catch (CompilationFailedException e) {
            throw e;
        }

        // work around to refresh R.java file
        File resourceClass = module.getJavaFile(module.getPackageName() + ".R");
        if (resourceClass != null) {
            JavaCompilerProvider provider =
                    CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
            JavaCompilerService service = provider.getCompiler(project, module);

            CompilerContainer container = service.compile(resourceClass.toPath());
            container.run(__ -> {

            });
        }
    }
}
