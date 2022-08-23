package com.tyron.completion.java.parse;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Pair;
import com.tyron.common.util.DebouncerStore;
import com.tyron.completion.java.compiler.services.NBEnter;
import com.tyron.completion.java.compiler.services.NBLog;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.tools.JavaFileObject;

public class CompilationInfo {

    public static final Key<CompilationInfo> COMPILATION_INFO_KEY = Key.create("compilationInfo");

    public final CompilationInfoImpl impl;
    private final Map<URI, JCCompilationUnit> compiledMap = new HashMap<>();

    private final DebouncerStore<String> debouncerStore = DebouncerStore.DEFAULT;

    private final Object parseLock = new Object();

    public CompilationInfo(final CompilationInfoImpl impl) {
        assert impl != null;
        this.impl = impl;
    }

    public JCCompilationUnit updateImmediately(JavaFileObject fileObject) {
        CompletableFuture<JCCompilationUnit> future = new CompletableFuture<>();
        update(fileObject, 0, future::complete);
        try {
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            return null;
        }
    }

    public void update(JavaFileObject fileObject) {
        this.update(fileObject, 300, __ -> {
        });
    }

    public void update(JavaFileObject fileObject, long delay) {
        this.update(fileObject, delay, __ -> {
        });
    }

    public synchronized void update(JavaFileObject fileObject,
                                    long delay,
                                    Consumer<JCCompilationUnit> treeConsumer) {
        debouncerStore.registerOrGetDebouncer("update").debounce(delay, () -> {
            synchronized (parseLock) {
                JavacTaskImpl javacTask = impl.getJavacTask();

                NBLog log = NBLog.instance(javacTask.getContext());
                log.useSource(fileObject);
                log.startPartialReparse(fileObject);

                Set<Pair<JavaFileObject, Integer>> toRemove = new HashSet<>();
                for (Pair<JavaFileObject, Integer> pair : log.getRecorded()) {
                    if (pair.fst.toUri().equals(fileObject.toUri())) {
                        toRemove.add(pair);
                    }
                }
                log.getRecorded().removeAll(toRemove);

                if (compiledMap.containsKey(fileObject.toUri())) {
                    JCCompilationUnit tree = compiledMap.get(fileObject.toUri());
                    NBEnter enter = (NBEnter) NBEnter.instance(javacTask.getContext());
                    enter.unenter(tree, tree);
                    enter.removeCompilationUnit(fileObject);
                }

                // reparse the whole file
                Iterable<? extends CompilationUnitTree> units;
                try {
                    units = javacTask.parse(fileObject);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                if (!units.iterator().hasNext()) {
                    return;
                }
                javacTask.analyze();

                log.endPartialReparse(fileObject);
                compiledMap.put(fileObject.toUri(), (JCCompilationUnit) units.iterator().next());

                treeConsumer.accept((JCCompilationUnit) units.iterator().next());
            }
        });
    }

    public JCCompilationUnit getCompilationUnit(JavaFileObject fileObject) {
        return getCompilationUnit(fileObject.toUri());
    }

    public JCCompilationUnit getCompilationUnit(URI uri) {
        return compiledMap.get(uri);
    }
}
