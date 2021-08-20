package com.tyron.code.compiler;
import com.tyron.code.editor.log.LogViewModel;
import java.util.List;
import java.io.File;
import com.tyron.code.parser.FileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;
import com.sun.tools.javac.file.JavacFileManager;
import com.tyron.code.ApplicationLoader;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.charset.Charset;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TaskEvent;
import javax.tools.DiagnosticListener;
import javax.tools.DiagnosticCollector;
import javax.tools.Diagnostic;
import java.util.Locale;

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
    
    public void compile(final String content, OnCompleteListener listener) {
        Context context = new Context();
        
        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        JavacFileManager fileManager = mTool.getStandardFileManager(diag, Locale.ENGLISH, Charset.defaultCharset());
        try {
            File file = new File(internalFileManager.getAndroidJar().getAbsolutePath());
            fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, List.of(file));
            fileManager.setLocation(StandardLocation.CLASS_PATH, List.of(FileManager.getInstance().getLambdaStubs()));
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(ApplicationLoader.applicationContext.getCacheDir()));
        } catch (IOException e) {
            ApplicationLoader.showToast(e.getMessage());
        }
        
        SimpleJavaFileObject source = new SimpleJavaFileObject(URI.create("Test.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreErrors) {
                return content;
            }
        };
        
        
        
        JavacTask task = JavacTool.create().getTask(null, fileManager, diag, null, null, List.of(source));
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
}
