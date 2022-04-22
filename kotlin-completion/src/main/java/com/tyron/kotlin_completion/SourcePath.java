package com.tyron.kotlin_completion;

import android.util.Log;

import com.tyron.kotlin_completion.compiler.CompletionKind;
import com.tyron.kotlin_completion.index.SymbolIndex;
import com.tyron.kotlin_completion.util.AsyncExecutor;
import com.tyron.kotlin_completion.util.UtilKt;

import org.apache.commons.io.FileUtils;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.descriptors.ModuleDescriptor;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingContext;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import kotlin.collections.CollectionsKt;
import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;
import kotlin.Pair;

public class SourcePath {

    private static final String TAG = "SourcePath";

    private final CompilerClassPath cp;
    private final Map<URI, SourceFile> files = new HashMap<>();
    private final FakeLock parsedDataWriteLock = new FakeLock();

    public static class FakeLock {
        public void lock() {}
        public void unlock() {}
    }

    private final AsyncExecutor indexAsync = new AsyncExecutor();
    private final SymbolIndex index = new SymbolIndex();
    private boolean indexEnabled = false;
    private boolean indexInitialized;


    public SourcePath(CompilerClassPath classPath) {
        cp = classPath;
    }

    public CompilerClassPath getCompilerClassPath() {
        return cp;
    }

    public SymbolIndex getIndex() {
        return index;
    }

    public class SourceFile {

        private final URI uri;
        private String content;
        private final Path path;
        private KtFile parsed;
        private KtFile compiledFile;
        public BindingContext compiledContext;
        private ComponentProvider compiledcontainer;
        private final Language language;
        private final boolean isTemporary;

        private final String extension;
        private final CompletionKind kind = CompletionKind.DEFAULT;

        public SourceFile(URI uri, String content, Language language, boolean isTemporary) {
            this(uri, content, Paths.get(uri), null, null, null, null, language, isTemporary);
        }
        public SourceFile(URI uri, String content, Language language) {
            this(uri, content, Paths.get(uri), null, null, null, null, language, false);
        }

        private SourceFile(URI uri, String content, Path path, KtFile parsed, KtFile compiledFile, BindingContext compiledContext, ComponentProvider compiledcontainer, Language language, boolean isTemporary) {
            this.uri = uri;
            this.content = content;
            this.path = path;
            this.parsed = parsed;
            this.compiledFile = compiledFile;
            this.compiledContext = compiledContext;
            this.compiledcontainer = compiledcontainer;
            this.language = language;
            this.isTemporary = isTemporary;

            extension = ".kt";
        }

        public void put(String newContent) {
            content = newContent;
        }

        public void clean() {
            parsed = null;
            compiledFile = null;
            compiledContext = null;
            compiledcontainer = null;
        }

        public void parse() {
            Log.d(TAG, "Parsing file " + path);
            parsed = cp.getCompiler().createKtFile(content, (path == null ? Paths.get("sourceFile.virtual" + extension) : path), kind);
        }

        public void parseIfChanged() {
            if (parsed == null || !content.equals(parsed.getText())) {
                Log.d(TAG, "Parse has changed, parsing.");
                parse();
            }
        }

        public void compileIfNull() {
            if (compiledFile == null) {
                parseIfChanged();
                doCompileIfChanged();
            }
        }

        private void compile() {
            parse();
            doCompile();
        }

        private void compileIfChanged() {
            parseIfChanged();
            doCompileIfChanged();
        }

        private void doCompileIfChanged() {
            if (parsed == null || compiledFile == null || !parsed.getText().equals(compiledFile.getText())) {
                doCompile();;
            }
        }

        private void doCompile() {
            if (this.path.toFile().getName().endsWith(".kt")) {
                Pair<BindingContext, ComponentProvider> pair = cp.getCompiler().compileKtFile(parsed, allIncludingThis());
                parsedDataWriteLock.lock();
                try {
                    compiledContext = pair.getFirst();
                    compiledcontainer = pair.getSecond();
                    compiledFile = parsed;
                } finally {
                    parsedDataWriteLock.unlock();
                }
            }
            initializeIndexAsyncIfNeeded(compiledcontainer);
        }
        public CompiledFile prepareCompiledFile() {
            parseIfChanged();
            compileIfNull();
            return doPrepareCompiledFile();
        }

        public CompiledFile doPrepareCompiledFile() {
            return new CompiledFile(content, compiledFile, compiledContext, compiledcontainer, allIncludingThis(), cp);
        }

        private Collection<KtFile> allIncludingThis() {
            parseIfChanged();
            if (isTemporary) {
                Set<KtFile> all = all(false);
                Sequence<KtFile> plus = SequencesKt.plus(SequencesKt.asSequence(all.iterator()), SequencesKt.sequenceOf(parsed));
                return SequencesKt.toList(plus);
            } else {
                return all(false);
            }
        }

    }

    public void put(File file, String content, boolean temp) {
        assert !content.contains("\r");

        Log.d(TAG, "Putting contents of " + file.getName());
        if (temp) {
            Log.d(TAG, "Adding temporary file");
        }

        if (files.containsKey(file.toURI())) {
            sourceFile(file).put(content);
        } else {
            files.put(file.toURI(), new SourceFile(file.toURI(), content, KotlinLanguage.INSTANCE, temp));
        }
    }

    public boolean deleteIfTemporary(File uri) {
        if (sourceFile(uri).isTemporary) {
            delete(uri);
            return true;
        }

        return false;
    }

    public void delete(File file) {
        files.remove(file.toURI());
    }

    public BindingContext compileFiles(Collection<File> all) {
        Set<SourceFile> sources = all.stream().map(o -> files.get(o.toURI())).collect(Collectors.toSet());
        Set<SourceFile> allChanged = sources.stream().filter(it -> {
            if (it.compiledFile == null) {
                return true;
            }
            return !it.content.equals(it.compiledFile.getText());
        })
                .collect(Collectors.toSet());
        BindingContext sourcesContext = compileAndUpdate(allChanged);
        return UtilKt.util(sourcesContext, sources, allChanged);
    }

    private void initializeIndexAsyncIfNeeded(ComponentProvider container) {
        indexAsync.execute(() -> {
            if (indexEnabled && !indexInitialized) {
                ModuleDescriptor module = (ModuleDescriptor) container.resolve(ModuleDescriptor.class).getValue();
                index.refresh(module, true);
                indexInitialized = true;
            }
        });
    }


    private BindingContext compileAndUpdate(Set<SourceFile> changed) {
        if (changed.isEmpty()) return null;
        Map<SourceFile, KtFile> parse = CollectionsKt.associateWith(changed, sourceFile -> {
            sourceFile.parseIfChanged();
            return sourceFile.parsed;
        });
        Set<KtFile> all = all(false);
        Pair<BindingContext, ComponentProvider> pair = cp.getCompiler()
                .compileKtFiles(parse.values(), all, CompletionKind.DEFAULT);

        parse.forEach((f, parsed) -> {
            parsedDataWriteLock.lock();
            try {
                if (f.parsed.equals(parsed)) {
                    f.compiledFile = parsed;
                    f.compiledContext = pair.getFirst();
                    f.compiledcontainer = pair.getSecond();
                }
            } finally {
                parsedDataWriteLock.unlock();
            }
        });

        initializeIndexAsyncIfNeeded(pair.getSecond());
        return pair.getFirst();
    }

    public CompiledFile currentVersion(File file) {
        SourceFile sourceFile = sourceFile(file);
        sourceFile.compileIfChanged();
        return sourceFile.prepareCompiledFile();
    }

    public CompiledFile latestCompiledVersion(File file) {
        SourceFile sourceFile = sourceFile(file);
        return sourceFile.prepareCompiledFile();
    }

    private SourceFile sourceFile(File file) {
        if (!files.containsKey(file.toURI())) {
            String string;
            try {
                string = FileUtils.readFileToString(file, Charset.defaultCharset());
                Log.d("STRING", string);
            } catch (IOException e) {
                string = "";
            }
            put(file, string, true);
        }
        return files.get(file.toURI());
    }
    private Set<KtFile> all(boolean includeHidden) {
        return files.values().stream()
                .filter(it -> includeHidden || !it.isTemporary)
                .map(it -> {
                    it.parseIfChanged();
                    return it.parsed;
                }).collect(Collectors.toSet());
    }

}
