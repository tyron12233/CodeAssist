package com.tyron.code;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.tyron.code.compiler.CompileBatch;
import com.tyron.code.compiler.ReusableCompiler;
import java.util.Map;
import java.util.HashMap;
import com.tyron.code.parser.FileManager;

import android.annotation.SuppressLint;
import android.util.Log;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public class JavaCompilerService implements CompilerProvider {

    public final SourceFileManager fileManager;

    public final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();

	public final Set<File> classPath, docPath;
	public final Set<String> addExports;
	public final ReusableCompiler compiler = new ReusableCompiler();

    public JavaCompilerService(Set<File> classPath, Set<File> docPath, Set<String> addExports) {

		this.classPath = Collections.unmodifiableSet(classPath);
		this.docPath = Collections.unmodifiableSet(docPath);
		this.addExports = Collections.unmodifiableSet(addExports);
		this.fileManager = new SourceFileManager();
    }

	public CompileBatch cachedCompile;

	private final Map<JavaFileObject, Long> cachedModified = new HashMap<>();

    private synchronized boolean needsCompile(Collection<? extends JavaFileObject> sources) {
        if (cachedModified.size() != sources.size()) {
            return true;
        }
        for (JavaFileObject f : sources) {
            if (!cachedModified.containsKey(f)) {
                return true;
            }
            Long cached = cachedModified.get(f);
            if (cached == null) {
                return true;
            }
            if (f.getLastModified() != cached) {
                return true;
            }
        }
        return false;
    }

    private synchronized void loadCompile(Collection<? extends JavaFileObject> sources) {
        if (cachedCompile != null) {
            if (!cachedCompile.closed) {
                throw new RuntimeException("Compiler is still in-use!");
            }
            cachedCompile.borrow.close();
        }
        cachedCompile = doCompile(sources);
        cachedModified.clear();
        for (JavaFileObject f : sources) {
            cachedModified.put(f, f.getLastModified());
        }
    }

    private synchronized CompileBatch doCompile(Collection<? extends JavaFileObject> sources) {
        if (sources.isEmpty()) throw new RuntimeException("empty sources");
        CompileBatch firstAttempt = new CompileBatch(this, sources);
        Set<Path> addFiles = firstAttempt.needsAdditionalSources();
        if (addFiles.isEmpty()) return firstAttempt;
        // If the compiler needs additional source files that contain package-private files
		//  LOG.info("...need to recompile with " + addFiles);
	    Log.d("JavaCompilerService", "Need to recompile with " + addFiles);
        firstAttempt.close();
        firstAttempt.borrow.close();
        List<JavaFileObject> moreSources = new ArrayList<>(sources);
        for (Path add : addFiles) {
            moreSources.add(new SourceFileObject(add));
        }
        return new CompileBatch(this, moreSources);
    }

    private synchronized CompileBatch compileBatch(Collection<? extends JavaFileObject> sources) {
			if (needsCompile(sources)) {
				loadCompile(sources);
			} else {
				Log.d("JavaCompilerService", "Using cached compile");
			}
			return cachedCompile;
    }


    @Override
    public Set<String> imports() {
        return null;
    }


    @Override
    public List<String> publicTopLevelTypes() {
        List<String> classes = new ArrayList<>();
		classes.addAll(FileManager.getInstance().all());
		classes.addAll(Collections.emptyList());
		return classes;
    }

    @Override
    public List<String> packagePrivateTopLevelTypes(String packageName) {
        return Collections.emptyList();
    }

    @Override
    public Iterable<Path> search(String query) {
        return null;
    }

    @SuppressLint("NewApi")
    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        return Optional.empty();
    }

    @Override
    public Path findTypeDeclaration(String className) {
        return null;
    }

    @Override
    public Path[] findTypeReferences(String className) {
        return null;
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        return null;
    }

    @Override
    public ParseTask parse(Path file) {
        Parser parser = Parser.parseFile(file);
		return new ParseTask(parser.task, parser.root);
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
		Parser parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    @Override
    public CompileTask compile(Path... files) {
        List<JavaFileObject> sources = new ArrayList<>();
        for (Path f : files) {
            sources.add(new SourceFileObject(f));
        }
        return compile(sources);
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        CompileBatch compile = compileBatch(sources);
		return new CompileTask(compile.task, compile.roots, diagnostics, compile::close);
    }

}
