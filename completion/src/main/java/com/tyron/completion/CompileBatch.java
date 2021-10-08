package com.tyron.completion;

import android.util.Log;

import org.apache.commons.io.FileUtils;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.Trees;

import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.StringSearch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.openjdk.javax.lang.model.element.Name;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.javax.lang.model.util.Types;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.util.DiagnosticSource;
import org.openjdk.tools.javac.util.JCDiagnostic;


@SuppressWarnings("NewApi")
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
        Set<Path> addFiles = new HashSet<>();
        for (Diagnostic<? extends JavaFileObject> err : parent.diagnostics) {
            if (!err.getCode().equals("compiler.err.cant.resolve.location")) {
                continue;
            }
            if (!isValidFileRange(err)) {
                continue;
            }
            String className;
            try {
                className = errorText(err);
            } catch (IOException e) {
                continue;
            }
            if (className == null) {
                continue;
            }

            String packageName = packageName(err);
            Path location = findPackagePrivateClass(packageName, className);
            if (location != FILE_NOT_FOUND) {
                addFiles.add(location);
            }
        }

		Log.d("CompileBatch", "Additional sources: " + addFiles.toString());
        return addFiles;
    }

    private String errorText(Diagnostic<? extends JavaFileObject> err) throws IOException {

        if (err instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper) {
            JCDiagnostic diagnostic = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) err).d;
            String className = String.valueOf(diagnostic.getArgs()[1]);
            if (!className.equals("null")) {
                return className;
            }
        }
        // fallback to parsing the file

        Path file = Paths.get(err.getSource().toUri());
        String contents = FileUtils.readFileToString(file.toFile(), Charset.defaultCharset());
        int begin = (int) err.getStartPosition();
        int end = (int) err.getEndPosition();
        if (begin < 0 || end > contents.length()) {
            Log.w("CompileBatch", "Diagnostic position does not match with the contents");
            return null;
        }
        return contents.substring(begin, end);
    }

    private String packageName(Diagnostic<? extends JavaFileObject> err) {
        Path file = Paths.get(err.getSource().toUri());
        return StringSearch.packageName(file.toFile());
    }

    private static final Path FILE_NOT_FOUND = Paths.get("");

    private Path findPackagePrivateClass(String packageName, String className) {
        // This is too expensive, parsing each file causing completions to slow down
        // on large files
//
//        for (File file : FileManager.getInstance().list(packageName)) {
//			if (!file.exists()) {
//			    continue;
//            }
//            Parser parse = Parser.parseFile(file.toPath());
//            for (Name candidate : parse.packagePrivateClasses()) {
//                if (candidate.contentEquals(className)) {
//                    return file.toPath();
//                }
//            }
//        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static ReusableCompiler.Borrow batchTask(
		JavaCompilerService parent, Collection<? extends JavaFileObject> sources) {
        parent.diagnostics.clear();
        List<String> options = options(parent.classPath, parent.addExports);
        return parent.compiler.getTask(parent.fileManager, parent.diagnostics::add, options, Collections.emptyList(), sources);
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<File> classOrSourcePath) {
        return classOrSourcePath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<File> classPath, Set<String> addExports) {
        List<String> list = new ArrayList<>();

        Collections.addAll(list, "-cp", joinPath(classPath));
        Collections.addAll(list, "-bootclasspath", joinPath(Arrays.asList(FileManager.getInstance().getAndroidJar(), FileManager.getInstance().getLambdaStubs())));
//        Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        //Collections.addAll(list, "-verbose");
        Collections.addAll(list, "-proc:none");
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

    private boolean isValidFileRange(Diagnostic<? extends JavaFileObject> d) {
        return d.getSource().toUri().getScheme().equals("file") && d.getStartPosition() >= 0 && d.getEndPosition() >= 0;
    }
}
