package com.tyron.kotlin_completion;

import android.util.Log;

import com.tyron.builder.parser.FileManager;
import com.tyron.kotlin_completion.compiler.CompletionKind;

import org.apache.commons.io.FileUtils;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTrace;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;

public class SourcePath {

    private static final String TAG = "SourcePath";

    private final CompilerClassPath cp;
    private final Map<URI, SourceFile> files = new HashMap<>();
    private final ReentrantLock parsedDataWriteLock = new ReentrantLock();

    public SourcePath(CompilerClassPath classPath) {
        cp = classPath;
    }
    private class SourceFile {

        private final URI uri;
        private String content;
        private final Path path;
        private KtFile parsed;
        private KtFile compiledFile;
        private BindingContext compiledContext;
        private ComponentProvider compiledcontainer;
        private final Language language;
        private final boolean isTemporary;

        private final String extension;
        private final CompletionKind kind = CompletionKind.DEFAULT;

        public SourceFile(URI uri, String content) {
            this(uri, content, Paths.get(uri), null, null, null, null, null, false);
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
            parsed = cp.getCompiler().createKtFile(content, (path == null ? Paths.get("sourcePath.virtual" + extension) : path), kind);
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
            Pair<BindingContext, ComponentProvider> pair = cp.getCompiler().compileKtFile(parsed, allIncludingThis());
            parsedDataWriteLock.lock();
            try {
                compiledContext = pair.getFirst();
                compiledcontainer = pair.getSecond();
                compiledFile = parsed;
            } finally {
                parsedDataWriteLock.unlock();
            }
           // initializeIndexAsyncIfNeeded();
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
                Collection<KtFile> all = all(false);
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

        }

        if (files.containsKey(file.toURI())) {
            sourceFile(file).put(content);
        } else {
            files.put(file.toURI(), new SourceFile(file.toURI(), content));
        }
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
            } catch (IOException e) {
                string = "";
            }
            put(file, string, false);
        }
        return files.get(file.toURI());
    }
    private Collection<KtFile> all(boolean includeHidden) {
        return files.values().stream()
                .filter(it -> includeHidden || !it.isTemporary)
                .map(it -> {
                    it.parseIfChanged();

                    return it.parsed;
                }).collect(Collectors.toSet());
    }

}
