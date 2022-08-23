package com.tyron.completion.java.parse;

import com.sun.tools.javac.api.JavacTaskImpl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;

public class CompilationInfoImpl {

    private JavacTaskImpl javacTask;
    private DiagnosticListener<? super JavaFileObject> diagnosticListener;

    private File file;
    private File root;
    private JavacParser parser;

    public CompilationInfoImpl(
            JavacParser parser,
            File file,
            File root,
            final JavacTaskImpl javacTask,
            final DiagnosticListener<JavaFileObject> diagnosticListener
    ) {
        this.parser = parser;
        this.file = file;
        this.root = root;
        this.javacTask = javacTask;
        this.diagnosticListener = diagnosticListener;
    }

    /**
     * Returns {@link JavacTaskImpl}, when it doesn't exist
     * it's created.
     * @return JavacTaskImpl
     */
    public synchronized JavacTaskImpl getJavacTask() {
        try {
            return getJavacTask(Collections.emptyList());
        } catch (IOException ex) {
            //should not happen
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Returns {@link JavacTaskImpl}, when it doesn't exist
     * it's created.
     * @return JavacTaskImpl
     */
    public synchronized JavacTaskImpl getJavacTask(List<FileObject> forcedSources) throws IOException {
        if (javacTask == null) {
            diagnosticListener = new DiagnosticListenerImpl();
            javacTask = JavacParser.createJavacTask(this.file, Collections.emptyList(), this.root, Collections.emptyList(),
                    this.parser, diagnosticListener, false);
        }
        return javacTask;
    }

    public static class DiagnosticListenerImpl implements DiagnosticListener<JavaFileObject> {
        private final Map<JavaFileObject, Diagnostics> source2Errors = new HashMap<>();

        @Override
        public void report(Diagnostic<? extends JavaFileObject> message) {
            Diagnostics errors = getErrors(message.getSource());
            errors.add((int) message.getPosition(), message);
        }

        private Diagnostics getErrors(JavaFileObject file) {
            if (source2Errors.get(file) == null) {
                source2Errors.put(file, new Diagnostics());
            }
            return source2Errors.get(file);
        }
    }

    private static final class Diagnostics extends TreeMap<Integer, Collection<DiagNode>> {
        private DiagNode first;
        private DiagNode last;

        public void add(int pos, Diagnostic<? extends JavaFileObject> diag) {
            Collection<DiagNode> nodes = get((int)diag.getPosition());
            if (nodes == null) {
                put((int) diag.getPosition(), nodes = new ArrayList<>());
            }
            DiagNode node = new DiagNode(last, diag, null);
            nodes.add(node);
            if (last != null) {
                last.next = node;
            }
            last = node;
            if (first == null) {
                first = node;
            }
        }

        private void unlink(DiagNode node) {
            if (node.next == null) {
                last = node.prev;
            } else {
                node.next.prev = node.prev;

            }
            if (node.prev == null) {
                first = node.next;
            } else {
                node.prev.next = node.next;
            }
        }
    }

    private static final class DiagNode {
        private Diagnostic<? extends JavaFileObject> diag;
        private DiagNode next;
        private DiagNode prev;

        private DiagNode(DiagNode prev, Diagnostic<? extends JavaFileObject> diag, DiagNode next) {
            this.diag = diag;
            this.next = next;
            this.prev = prev;
        }
    }
}
