package com.tyron.code;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.StandardLocation;

public class JavaCompilerService implements CompilerProvider {

    public final SourceFileManager fileManager;

    public final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();

	public final Set<File> classPath, docPath;
	public final Set<String> addExports;
	public final ReusableCompiler compiler = new ReusableCompiler();
	private final Docs docs;

    public JavaCompilerService(Set<File> classPath, Set<File> docPath, Set<String> addExports) {

		this.classPath = Collections.unmodifiableSet(classPath);
		this.docPath = Collections.unmodifiableSet(docPath);
		this.addExports = Collections.unmodifiableSet(addExports);

		this.fileManager = new SourceFileManager();
		this.docs = new Docs(docPath);

        try {
            fileManager.setLocation(StandardLocation.CLASS_PATH, classPath);
            fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, Arrays.asList(
                    FileManager.getInstance().getAndroidJar(),
                    FileManager.getInstance().getLambdaStubs()
            ));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

	public CompileBatch cachedCompile;

	private final Map<JavaFileObject, Long> cachedModified = new HashMap<>();

    /**
     * Checks whether this list has been compiled before
     * @param sources list of java files to compile
     * @return true if there's a valid cache for it, false otherwise
     */
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

    /**
     * Creates a compile batch only if it has not been compiled before
     * @param sources Files to compile
     * @return CompileBatch for this compilation
     */
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

    // TODO: This doesn't list all the public types
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

    /**
     * Finds all the occurrences of a class in javadocs, and source files
     * @param className fully qualified name of the class
     * @return Optional of type JavaFileObject that may be empty if the file is not found
     */
    @SuppressLint("NewApi")
    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        Optional<JavaFileObject> fromDocs = findPublicTypeDeclarationInDocPath(className);
        if (fromDocs.isPresent()) {
            return fromDocs;
        }

        Path fromSource = findTypeDeclaration(className);
        if (fromSource != NOT_FOUND) {
            return Optional.of(new SourceFileObject(fromSource));
        }

        return Optional.empty();
    }

    /**
     * Searches the javadoc file manager if it contains the classes with javadoc
     * @param className the fully qualified name of the class
     * @return optional of type JavaFileObject, may be empty if it doesn't exist
     */
    private Optional<JavaFileObject> findPublicTypeDeclarationInDocPath(String className) {
        try {
            JavaFileObject found = docs.fileManager.getJavaFileForInput(
                    StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
            return Optional.ofNullable(found);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Path findTypeDeclaration(String className) {
        return NOT_FOUND ;
    }


    @Override
    public Path[] findTypeReferences(String className) {
        return null;
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        return null;
    }

    /**
     * Convenience method for parsing a path
     * @param file Path of java file to compile
     * @return ParseTask for this compilation
     */
    @Override
    public ParseTask parse(Path file) {
        Parser parser = Parser.parseFile(file);
		return new ParseTask(parser.task, parser.root);
    }

    /**
     * Parses a single java file without analysing and parsing other files
     * @param file Java file to parse
     * @return ParseTask for this compilation
     */
    @Override
    public ParseTask parse(JavaFileObject file) {
		Parser parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    /**
     * Convenience method to compile a list of paths, this just wraps them in a
     * SourceFileObject and calls {@link JavaCompilerService#compile(Collection)}
     * @param files list of java paths to compile
     * @return a CompileTask for this compilation
     */
    @Override
    public CompileTask compile(Path... files) {
        List<JavaFileObject> sources = new ArrayList<>();
        for (Path f : files) {
            sources.add(new SourceFileObject(f));
        }
        return compile(sources);
    }

    /**
     * Compiles a list of {@link JavaFileObject} not all of them needs no be compiled if
     * they have been compiled before
     * @param sources list of java sources
     * @return a CompileTask for this compilation
     */
    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        CompileBatch compile = compileBatch(sources);
		return new CompileTask(compile.task, compile.roots, diagnostics, compile::close);
    }

}
