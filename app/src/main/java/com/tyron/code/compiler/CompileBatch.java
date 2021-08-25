package com.tyron.code.compiler;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.util.*;
import javax.tools.*;
import com.tyron.code.JavaCompilerService;
import com.tyron.code.parser.FileManager;
import com.tyron.code.util.StringSearch;
import com.tyron.code.Parser;
import javax.lang.model.element.Name;
import android.util.Log;

public class CompileBatch implements AutoCloseable {
    static final int MAX_COMPLETION_ITEMS = 50;

    public final JavaCompilerService parent;
    public final ReusableCompiler.Borrow borrow;
    /** Indicates the task that requested the compilation is finished with it. */
    public boolean closed;

    public final JavacTask task;
    public final Trees trees;
    public final Elements elements;
    public final Types types;
    public final List<CompilationUnitTree> roots;

    public CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files) {
        this.parent = parent;
        this.borrow = batchTask(parent, files);
        this.task = borrow.task;
        this.trees = Trees.instance(borrow.task);
        this.elements = borrow.task.getElements();
        this.types = borrow.task.getTypes();
        this.roots = new ArrayList<>();
        // Compile all roots
        try {
            for (CompilationUnitTree t : borrow.task.parse()) {
                roots.add(t);
            }
            // The results of borrow.task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            borrow.task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * If the compilation failed because javac didn't find some package-private files in source files with different
     * names, list those source files.
     */
    public Set<Path> needsAdditionalSources() {
        // Check for "class not found errors" that refer to package private classes
        Set<Path> addFiles = new HashSet<Path>();
        for (Diagnostic err : parent.diags) {		
            if (!err.getCode().equals("compiler.err.cant.resolve.location")) continue;
            if (!isValidFileRange(err)) continue;
            String className = errorText(err);
            String packageName = packageName(err);
			Log.d("CompileBatch", "Searching for class: " + className + " package name: " + packageName);
            Path location = findPackagePrivateClass(packageName, className);
            if (location != FILE_NOT_FOUND) {
				Log.d("CompileBatch", "Found additional source: " + location);
                addFiles.add(location);
            }
        }
		
		Log.d("CompileBatch", "Additional sources: " + addFiles.toString());
        return addFiles;
    }

    private String errorText(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> err) {
        Path file = Paths.get(err.getSource().toUri());
        String contents = FileManager.getInstance().readFile(file.toFile());
        int begin = (int) err.getStartPosition();
        int end = (int) err.getEndPosition();
        return contents.substring(begin, end);
    }

    private String packageName(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> err) {
        Path file = Paths.get(err.getSource().toUri());
        return StringSearch.packageName(file.toFile());
    }

    private static final Path FILE_NOT_FOUND = Paths.get("");

    private Path findPackagePrivateClass(String packageName, String className) {
        for (File file : FileManager.getInstance().list(packageName)) {
			Log.d("CompileBatch", "Parsing file: " + file.getName());
            Parser parse = Parser.parseFile(file.toPath());
            for (Name candidate : parse.packagePrivateClasses()) {
				Log.d("CompileBatch", "Candidate: " + candidate);
                if (candidate.contentEquals(className)) {
                    return file.toPath();
                }
            }
        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static ReusableCompiler.Borrow batchTask(
		JavaCompilerService parent, Collection<? extends JavaFileObject> sources) {
        parent.diags.clear();
        List<String> options = options(parent.classPath, parent.addExports);
        return parent.compiler.getTask(parent.fileManager, parent.diags::add, options, List.of(), sources);
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<File> classOrSourcePath) {
        return classOrSourcePath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<File> classPath, Set<String> addExports) {
        List<String> list = new ArrayList<>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
		Collections.addAll(list, "-bootclasspath", joinPath(List.of(FileManager.getInstance().getAndroidJar(), FileManager.getInstance().getLambdaStubs())));
      //  Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        // Collections.addAll(list, "-verbose");
        Collections.addAll(list, "-proc:none");
        Collections.addAll(list, "-g");
        // You would think we could do -Xlint:all,
        // but some lints trigger fatal errors in the presence of parse errors
        Collections.addAll(
			list,
			"-Xlint:cast",
			"-Xlint:deprecation",
			"-Xlint:empty",
			"-Xlint:fallthrough",
			"-Xlint:finally",
			"-Xlint:path",
			"-Xlint:unchecked",
			"-Xlint:varargs",
			"-Xlint:static");
        for (String export : addExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }

        return list;
    }

    private boolean isValidFileRange(javax.tools.Diagnostic<? extends JavaFileObject> d) {
        return d.getSource().toUri().getScheme().equals("file") && d.getStartPosition() >= 0 && d.getEndPosition() >= 0;
    }
}
