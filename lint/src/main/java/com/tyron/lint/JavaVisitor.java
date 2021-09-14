package com.tyron.lint;

import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.completion.CompileTask;
import com.tyron.completion.CompilerProvider;
import com.tyron.completion.JavaCompilerService;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Detector.JavaScanner;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;

import org.openjdk.source.tree.AnnotationTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.TreeVisitor;
import org.openjdk.tools.javac.tree.JCTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaVisitor {

    private static final int SAME_TYPE_COUNT = 8;

    private final CompilerProvider mCompiler;
    private final List<VisitingDetector> mAllDetectors;
    private final Map<Class<? extends Tree>, List<VisitingDetector>> mTreeTypeDetectors =
            new HashMap<>(16);

    public JavaVisitor(JavaCompilerService compiler, @NonNull List<Detector> detectors) {
        mCompiler = compiler;
        mAllDetectors = new ArrayList<>(detectors.size());

        for (Detector detector : detectors) {
            VisitingDetector v = new VisitingDetector(detector, (JavaScanner) detector);
            mAllDetectors.add(v);

            List<Class<? extends Tree>> treeTypes = detector.getApplicableTypes();
            if (treeTypes != null) {
                for (Class<? extends Tree> tree : treeTypes) {
                    List<VisitingDetector> list = mTreeTypeDetectors.computeIfAbsent(tree, k -> new ArrayList<>(SAME_TYPE_COUNT));
                    list.add(v);
                }
            }
        }
    }

    public void visitFile(JavaContext context) {
        Tree compilationUnit = null;
        try {
            try (CompileTask task = mCompiler.compile(context.file.toPath())) {
                compilationUnit = task.root();
                context.setCompileTask(task);

                for (VisitingDetector v : mAllDetectors) {
                    v.setContext(context);
                }

                if (!mTreeTypeDetectors.isEmpty()) {
                    JavaVoidVisitor visitor = new DispatchVisitor();
                    compilationUnit.accept(visitor, null);
                }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private static class VisitingDetector {
        private JavaVoidVisitor mVisitor;
        private JavaContext mContext;
        public final Detector mDetector;
        public final JavaScanner mScanner;

        public VisitingDetector(@NonNull Detector detector, JavaScanner scanner) {
            mDetector = detector;
            mScanner = scanner;
        }

        @NonNull
        public Detector getDetector() {
            return mDetector;
        }

        @NonNull
        public JavaScanner getJavaScanner() {
            return mScanner;
        }

        public void setContext(@NonNull JavaContext context) {
            mContext = context;

            // The visitors are one-per-context, so clear them out here and construct
            // lazily only if needed
            mVisitor = null;
        }

        @NonNull
        JavaVoidVisitor getVisitor() {
            if (mVisitor == null) {
                mVisitor = mDetector.getVisitor(mContext);
            }
            return mVisitor;
        }
    }

    private class DispatchVisitor extends JavaVoidVisitor {

        @Override
        public Void visitAnnotation(AnnotationTree annotationTree, Void unused) {
            List<VisitingDetector> list =
                    mTreeTypeDetectors.get(AnnotationTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotation(annotationTree, unused);
                }
            }
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree methodInvocationTree, Void unused) {
            List<VisitingDetector> list =
                    mTreeTypeDetectors.get(MethodInvocationTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitMethodInvocation(methodInvocationTree, unused);
                }
            }
            return null;
        }

        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            List<VisitingDetector> list = mTreeTypeDetectors.get(MethodTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitMethod(methodTree, unused);
                }
            }
            return null;
        }
    }
}
