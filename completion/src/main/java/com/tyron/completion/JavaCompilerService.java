package com.tyron.completion;

import android.annotation.SuppressLint;
import android.util.Log;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.Cache;
import com.tyron.common.util.StringSearch;
import com.tyron.completion.provider.CompletionEngine;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.StandardLocation;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.tools.javac.file.PathFileObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaCompilerService implements CompilerProvider {

    public final SourceFileManager fileManager;

    public final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();

    public final Set<File> classPath, docPath;
    public final Set<String> addExports;
    public final ReusableCompiler compiler = new ReusableCompiler();
    private final Docs docs;

    public final ReentrantLock mLock = new ReentrantLock();

    public JavaCompilerService(Set<File> classPath, Set<File> docPath, Set<String> addExports) {

        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = Collections.unmodifiableSet(docPath);
        this.addExports = Collections.unmodifiableSet(addExports);

        this.fileManager = new SourceFileManager();
        this.docs = new Docs(docPath);
    }

    public CompileBatch cachedCompile;
    private final Object mCachedCompileLock = new Object();
    private final Map<JavaFileObject, Long> cachedModified = new HashMap<>();

    /**
     * Checks whether this list has been compiled before
     *
     * @param sources list of java files to compile
     * @return true if there's a valid cache for it, false otherwise
     */
    private boolean needsCompile(Collection<? extends JavaFileObject> sources) {
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

    private void loadCompile(Collection<? extends JavaFileObject> sources) {
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

    private CompileBatch doCompile(Collection<? extends JavaFileObject> sources) {
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
     *
     * @param sources Files to compile
     * @return CompileBatch for this compilation
     */
    private CompileBatch compileBatch(Collection<? extends JavaFileObject> sources) {

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
     *
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
     *
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

    private static final Pattern PACKAGE_EXTRACTOR = Pattern.compile("^([a-z][_a-zA-Z0-9]*\\.)*[a-z][_a-zA-Z0-9]*");

    private String packageName(String className) {
        Matcher m = PACKAGE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private static final Pattern SIMPLE_EXTRACTOR = Pattern.compile("[A-Z][_a-zA-Z0-9]*$");

    private String simpleName(String className) {
        Matcher m = SIMPLE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private static final Cache<String, Boolean> cacheContainsWord = new Cache<>();

    private boolean containsWord(Path file, String word) {
        if (cacheContainsWord.needs(file, word)) {
            cacheContainsWord.load(file, word, StringSearch.containsWord(file, word));
        }
        return cacheContainsWord.get(file, word);
    }

    private static final Cache<Void, List<String>> cacheContainsType = new Cache<>();

    private boolean containsType(Path file, String className) {
        if (cacheContainsType.needs(file, null)) {
            CompilationUnitTree root = parse(file).root;
            List<String> types = new ArrayList<>();
            new FindTypeDeclarations().scan(root, types);
            cacheContainsType.load(file, null, types);
        }
        return cacheContainsType.get(file, null).contains(className);
    }


    @Override
    public Path findTypeDeclaration(String className) {
        Path fastFind = findPublicTypeDeclaration(className);
        if (fastFind != NOT_FOUND) {
            return fastFind;
        }

        String packageName = packageName(className);
        String simpleName = simpleName(className);
        for (File file : FileManager.getInstance().list(packageName)) {
            if (containsWord(file.toPath(), simpleName) && containsType(file.toPath(), className)) {
                if (file.getName().endsWith(".java")) {
                    return file.toPath();
                }
            }
        }
        return NOT_FOUND;
    }

    private Path findPublicTypeDeclaration(String className) {
        JavaFileObject source;
        try {
            source =
                    fileManager.getJavaFileForInput(
                            StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (source == null) return NOT_FOUND;
        if (!source.toUri().getScheme().equals("file")) return NOT_FOUND;
        Path file = Paths.get(source.toUri());
        if (!containsType(file, className)) return NOT_FOUND;
        return file;
    }

    @Override
    public Path[] findTypeReferences(String className) {
        return null;
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        return null;
    }

    private Cache<String, ParseTask> parseCache = new Cache<>();

    private ParseTask cachedParse(Path file) {
        if (parseCache.needs(file, null)) {
            Parser parser = Parser.parseFile(file);
            parseCache.load(file, null, new ParseTask(parser.task, parser.root));
        }
        return parseCache.get(file, null);
    }

    private ParseTask cachedParse(JavaFileObject file) {
        if (file instanceof PathFileObject) {
            // workaround to get the file uri of a JarFileObject
            String path = file.toUri().toString()
                    .substring(4, file.toUri().toString().lastIndexOf("!"));
            Log.d("TEST", path);

            Path parsedPath = new File(URI.create(path)).toPath();
            if (parseCache.needs(parsedPath, file.getName())) {
                Parser parser = Parser.parseJavaFileObject(file);
                parseCache.load(parsedPath, file.getName(), new ParseTask(parser.task, parser.root));
            }
            return parseCache.get(parsedPath, file.getName());
        } else if (file instanceof SourceFileObject) {
            return cachedParse(((SourceFileObject) file).mFile);
        }

        Parser parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    /**
     * Convenience method for parsing a path
     *
     * @param file Path of java file to compile
     * @return ParseTask for this compilation
     */
    @Override
    public ParseTask parse(Path file) {
        return cachedParse(file);
    }

    /**
     * Parses a single java file without analysing and parsing other files
     *
     * @param file Java file to parse
     * @return ParseTask for this compilation
     */
    @Override
    public ParseTask parse(JavaFileObject file) {
        return cachedParse(file);
    }

    public ParseTask parse(Path file, String contents) {
        SourceFileObject sourceFileObject = new SourceFileObject(file, contents, Instant.now());
        Parser parser = Parser.parseJavaFileObject(sourceFileObject);
        parseCache.load(file, file.toFile().getName(),
                new ParseTask(parser.task, parser.root));
        return parseCache.get(file, file.toFile().getName());
    }

    /**
     * Convenience method to compile a list of paths, this just wraps them in a
     * SourceFileObject and calls {@link JavaCompilerService#compile(Collection)}
     *
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
     *
     * @param sources list of java sources
     * @return a CompileTask for this compilation
     */
    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        mLock.lock();
        CompileBatch compile = compileBatch(sources);
        return new CompileTask(compile.task, compile.roots, diagnostics, this::close);
    }

    public void close() {
        if (!cachedCompile.closed) {
            cachedCompile.close();
        }
        if (mLock.isHeldByCurrentThread() && mLock.isLocked()) {
            mLock.unlock();
        }
    }

    public boolean isReady() {
        if (CompletionEngine.isIndexing()) {
            return false;
        }
        if (cachedCompile == null) {
            return true;
        }
        return cachedCompile.closed;
    }

}
