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

public class JavaParser {

    private static final String TAG = "JavaParser";
    private static final String DOT = ".";
    private static final String CONSTRUCTOR_NAME = "<init>";

    private Context context;
    private ParserFactory parserFactory;
    private JavacFileManager fileManager;
    private FileManager internalFileManager = new FileManager();
    private DiagnosticCollector<JavaFileObject> diagnostics;
    private boolean canParse = true;
    
    private JavacTask task;
   
    public JavaParser() {
        context = new Context();
        diagnostics = new DiagnosticCollector<>();
        context.put(DiagnosticListener.class, diagnostics);
        
        Options.instance(context).put("allowStringFolding", "false");
        Options.instance(context).put("bootclasspath", internalFileManager.getAndroidJar().getAbsolutePath());
        
        fileManager = new JavacFileManager(context, true, Charset.defaultCharset());
        try {
            File file = new File(internalFileManager.getAndroidJar().getAbsolutePath());
            fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, List.of(file));
            internalFileManager.putJar(file);
        } catch (IOException e) {
            ApplicationLoader.showToast(e.getMessage());
            // impossible
            canParse = false;
        }
        parserFactory = ParserFactory.instance(context);              
    }

    public CompilationUnitTree parse(String src, int pos) {
        if (!canParse) return null;
        long time = System.currentTimeMillis();
        
        final StringBuilder fix = new StringBuilder(src);
        
        // We add an extra ';' to the end of the line so parsing will produce the right tokens
        if (pos != -1) {
            int end = CompletionProvider.endOfLine(src, pos);
            fix.insert(end, ';');
        }
        
        SimpleJavaFileObject source = new SimpleJavaFileObject(URI.create("Test.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return fix;          
            }
        };
        
        Log.instance(context).useSource(source);
        
        task = JavacTool.create().getTask(null, fileManager,
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
        all.addAll(internalFileManager.all());
        return all;
    }
}
