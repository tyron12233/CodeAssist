package com.tyron.completion.java.parse;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.DiagnosticFormatter;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;

public class CompilationInfoImpl {

    private CompilationUnitTree compilationUnit;

    private final List<File> classpath;
    private final List<File> sourcepath;
    private JavacTaskImpl javacTask;
    private DiagnosticListener<? super JavaFileObject> diagnosticListener;

    private File file;
    private File root;
    private JavacParser parser;

    public CompilationInfoImpl(
            JavacParser parser,
            File file,
            File root,
            List<File> classpath,
            List<File> sourcepath,
            final JavacTaskImpl javacTask,
            final DiagnosticListener<JavaFileObject> diagnosticListener
    ) {
        this.parser = parser;
        this.file = file;
        this.root = root;
        this.classpath = classpath;
        this.sourcepath = sourcepath;
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
            javacTask = JavacParser.createJavacTask(this.file, Collections.emptyList(), this.root, classpath, sourcepath,
                    this.parser, diagnosticListener, false);
        }
        return javacTask;
    }

    public CompilationUnitTree getCompilationUnit() {
        return compilationUnit;
    }

    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics(JavaFileObject fileObject) {
        DiagnosticListenerImpl.Diagnostics errors = ((DiagnosticListenerImpl)diagnosticListener).getErrors(fileObject);
        List<Diagnostic<? extends JavaFileObject>> partialReparseErrors = ((DiagnosticListenerImpl)diagnosticListener).partialReparseErrors;
        List<Diagnostic<? extends JavaFileObject>> affectedErrors = ((DiagnosticListenerImpl)diagnosticListener).affectedErrors;
        int errorsSize = 0;

        for (Collection<DiagnosticListenerImpl.DiagNode> err : errors.values()) {
            errorsSize += err.size();
        }

        List<Diagnostic<? extends JavaFileObject>> localErrors = new ArrayList<>(errorsSize +
                                                                 (partialReparseErrors == null ? 0 : partialReparseErrors.size()) +
                                                                 (affectedErrors == null ? 0 : affectedErrors.size()));
        DiagnosticFormatter<JCDiagnostic> formatter = Log.instance(javacTask.getContext()).getDiagnosticFormatter();
        DiagnosticListenerImpl.DiagNode node = errors.first;
        while(node != null) {
            localErrors.add(RichDiagnostic.wrap(node.diag, formatter));
            node = node.next;
        }

        if (partialReparseErrors != null) {
            for (Diagnostic<? extends JavaFileObject> d : partialReparseErrors) {
                localErrors.add(RichDiagnostic.wrap(d, formatter));
            }
        }
        if (affectedErrors != null) {
            for (Diagnostic<? extends JavaFileObject> d : affectedErrors) {
                localErrors.add(RichDiagnostic.wrap(d, formatter));
            }
        }
        return localErrors;
    }

    public static class DiagnosticListenerImpl implements DiagnosticListener<JavaFileObject> {
        private final Map<URI, Diagnostics> source2Errors = new HashMap<>();
        private volatile List<Diagnostic<? extends JavaFileObject>> partialReparseErrors;

        /**
         * true if the partialReparseErrors contain some non-warning
         */
        private volatile boolean partialReparseRealErrors;
        private volatile List<Diagnostic<? extends JavaFileObject>> affectedErrors;
        private volatile int currentDelta;

        @Override
        public void report(Diagnostic<? extends JavaFileObject> message) {
            if (partialReparseErrors != null) {
//                if (this.jfo != null && this.jfo == message.getSource()) {
//                    partialReparseErrors.add(message);
//                    if (message.getKind() == Diagnostic.Kind.ERROR) {
//                        partialReparseRealErrors = true;
//                    }
//                }
            } else {
                Diagnostics errors = getErrors(message.getSource());
                errors.add((int) message.getPosition(), message);
            }
        }

        private Diagnostics getErrors(JavaFileObject file) {
            if (source2Errors.get(file.toUri()) == null) {
                source2Errors.put(file.toUri(), new Diagnostics());
            }
            return source2Errors.get(file.toUri());
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

    static final class RichDiagnostic implements Diagnostic {

        private final JCDiagnostic delegate;
        private final DiagnosticFormatter<JCDiagnostic> formatter;

        public RichDiagnostic(JCDiagnostic delegate, DiagnosticFormatter<JCDiagnostic> formatter) {
            this.delegate = delegate;
            this.formatter = formatter;
        }

        @Override
        public Kind getKind() {
            return delegate.getKind();
        }

        @Override
        public Object getSource() {
            return delegate.getSource();
        }

        @Override
        public long getPosition() {
            return delegate.getPosition();
        }

        @Override
        public long getStartPosition() {
            return delegate.getStartPosition();
        }

        @Override
        public long getEndPosition() {
            return delegate.getEndPosition();
        }

        @Override
        public long getLineNumber() {
            return delegate.getLineNumber();
        }

        @Override
        public long getColumnNumber() {
            return delegate.getColumnNumber();
        }

        @Override
        public String getCode() {
            return delegate.getCode();
        }

        @Override
        public String getMessage(Locale locale) {
            return formatter.format(delegate, locale);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }

        JCDiagnostic getDelegate() {
            return delegate;
        }

        public static Diagnostic wrap(Diagnostic d, DiagnosticFormatter<JCDiagnostic> df) {
            if (d instanceof JCDiagnostic) {
                return new RichDiagnostic((JCDiagnostic) d, df);
            } else {
                return d;
            }
        }
    }
}
