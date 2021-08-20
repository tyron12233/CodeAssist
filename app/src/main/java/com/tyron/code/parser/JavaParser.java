package com.tyron.code.parser;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.Parser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.source.tree.CompilationUnitTree;
import java.util.Arrays;
import java.io.File;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.completion.CompletionProvider;
import java.util.Collection;



import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.util.regex.Matcher;
import java.io.IOException;
import com.tyron.code.editor.log.LogViewModel;
import java.util.Locale;
import com.sun.source.util.Trees;

public class JavaParser {

    private static final String TAG = "JavaParser";
    private static final String DOT = ".";
    private static final String CONSTRUCTOR_NAME = "<init>";
    
    private final JavacTool mTool = JavacTool.create();
    private final Context context;
    
    private final JavacFileManager fileManager;
   
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private boolean canParse = true;
    
    private JavacTask task;
    
    private final LogViewModel log;
   
    public JavaParser(LogViewModel log) {
        this.log = log;
        context = new Context();
        diagnostics = new DiagnosticCollector<>();
        
        fileManager = mTool.getStandardFileManager(diagnostics, Locale.ENGLISH, Charset.defaultCharset());
        try {
            File file = new File(FileManager.getInstance().getAndroidJar().getAbsolutePath());
            fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, List.of(file, FileManager.getInstance().getLambdaStubs()));
            
            List<File> files = new ArrayList<>();
            files.add(ApplicationLoader.applicationContext.getFilesDir());
            files.add(FileManager.getInstance().getLambdaStubs());
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(ApplicationLoader.applicationContext.getFilesDir()));
            fileManager.setLocation(StandardLocation.CLASS_PATH, files);
        } catch (IOException e) {
            log.d(LogViewModel.DEBUG, e.getMessage());
            // impossible
            canParse = false;
        }
                     
    }

    public CompilationUnitTree parse(File file, String src, int pos) {
        if (!canParse) return null;
        long time = System.currentTimeMillis();
        
        final StringBuilder fix = new StringBuilder(src);
        
        // We add an extra ';' to the end of the line so parsing will produce the right tokens
        if (pos != -1) {
            int end = CompletionProvider.endOfLine(src, pos);
            fix.insert(end, ';');
        }
        
        SimpleJavaFileObject source = new SimpleJavaFileObject(file.toURI(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return fix;          
            }
        }; 
        
        task = mTool.getTask(null, fileManager,
                                          diagnostics, null, null, List.of(source));                                  
        CompilationUnitTree unit = null;
        try {
            unit = task.parse().iterator().next();
            task.analyze();
        } catch (IOException e) {}
        return unit;
    }
    
    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
        return diagnostics.getDiagnostics();
    }
    
    public JavacTask getTask() {
        return task;
    }
    
    public Context getContext() {
        return context;
    }
    
    public List<String> packagePrivateTopLevelTypes(String packageName) {
        return Collections.emptyList();
    }
    
    public List<String> publicTopLevelTypes() {
        List<String> all = new ArrayList<>();
        all.addAll(FileManager.getInstance().all());
        return all;
    }
}
