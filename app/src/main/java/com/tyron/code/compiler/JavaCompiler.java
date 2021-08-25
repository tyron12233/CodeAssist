package com.tyron.code.compiler;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.SourceFileObject;
import com.tyron.code.editor.log.LogViewModel;
import com.tyron.code.parser.FileManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

public class JavaCompiler {

    public interface OnCompleteListener {
        void onComplete(boolean successful);
    }

    private final JavacTool mTool = JavacTool.create();

    private FileManager internalFileManager;
    private LogViewModel log;

    public JavaCompiler(LogViewModel model)  {
        this.log = model;

        internalFileManager = FileManager.getInstance();
    }

    public void compile(OnCompleteListener listener) {

        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        JavacFileManager fileManager = mTool.getStandardFileManager(diag, Locale.ENGLISH, Charset.defaultCharset());
        try {
            File file = new File(internalFileManager.getAndroidJar().getAbsolutePath());
            fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, List.of(file));
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath());
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(FileManager.getInstance().getCurrentProject().getBuildDirectory()));
        } catch (IOException e) {
            ApplicationLoader.showToast(e.getMessage());
        }

        JavacTask task = JavacTool.create().getTask(null, fileManager, diag, null, null, getCompilationUnits());
        task.setTaskListener(new TaskListener() {
                @Override
                public void started(TaskEvent e) {
                    log.d(LogViewModel.BUILD_LOG, "[javac] " + e.getKind() + ": started");
                }

                @Override
                public void finished(TaskEvent e) {
                    log.d(LogViewModel.BUILD_LOG, "[javac] " + e.getKind() + ": finished");
                }                           
			});

        log.d(LogViewModel.BUILD_LOG, "[javac] Started");

        boolean success = task.call();
        for (Diagnostic d : diag.getDiagnostics()) {
            log.d(LogViewModel.BUILD_LOG, "[javac] " + d.getKind() + " LINE: " + d.getLineNumber() + ", COLUMN: " + d.getColumnNumber() + " MESSAGE: " + d.getMessage(Locale.ENGLISH));
        }
        log.d(LogViewModel.BUILD_LOG, "[javac] Finished");
        listener.onComplete(success);     

    }

	private List<JavaFileObject> getCompilationUnits() {
		List<JavaFileObject> files = new ArrayList<>();
	    for (File file : FileManager.getInstance().getCurrentProject().getJavaFiles().values()) {
			files.add(new SourceFileObject(file.toPath()));
		}
		return files;
	}

    private List<File> classpath() {
        List<File> files = new ArrayList<>();
        files.add(FileManager.getInstance().getLambdaStubs());
		files.add(FileManager.getInstance().getCurrentProject().getJavaDirectory());
		files.addAll(FileManager.getInstance().getCurrentProject().getLibraries());
        return files;
    }
}
