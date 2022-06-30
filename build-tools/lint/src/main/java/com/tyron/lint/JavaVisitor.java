package com.tyron.lint;

import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Detector.JavaScanner;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

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
    private final Map<String, List<VisitingDetector>> mMethodDetectors = new HashMap<>(16);

    public JavaVisitor(JavaCompilerService compiler, @NonNull List<Detector> detectors) {
        mCompiler = compiler;
        mAllDetectors = new ArrayList<>(detectors.size());

        for (Detector detector : detectors) {
            VisitingDetector v = new VisitingDetector(detector, (JavaScanner) detector);
            mAllDetectors.add(v);

            List<Class<? extends Tree>> treeTypes = detector.getApplicableTypes();
            if (treeTypes != null) {
                for (Class<? extends Tree> tree : treeTypes) {
                    List<VisitingDetector> list = mTreeTypeDetectors.computeIfAbsent(tree,
                            k -> new ArrayList<>(SAME_TYPE_COUNT));
                    list.add(v);
                }
            }

            List<String> names = detector.getApplicableMethodNames();
            if (names != null) {
                for (String name : names) {
                    List<VisitingDetector> list = mMethodDetectors.computeIfAbsent(name,
                            k -> new ArrayList<>(SAME_TYPE_COUNT));
                    list.add(v);
                }
            }
        }
    }

    public void visitFile(JavaContext context) {
        try {
            CompilerContainer container = mCompiler.compile(context.file.toPath());
            container.run(task -> {
                Tree compilationUnit = task.root();
                context.setCompileTask(task);

                for (VisitingDetector v : mAllDetectors) {
                    v.setContext(context);
                }

                if (!mMethodDetectors.isEmpty()) {
                    JavaVoidVisitor visitor = new DelegatingJavaVisitor(context);
                    compilationUnit.accept(visitor, null);
                } else if (!mTreeTypeDetectors.isEmpty()) {
                    JavaVoidVisitor visitor = new DispatchVisitor();
                    compilationUnit.accept(visitor, null);
                }
            });
        } catch (Throwable e) {
            Log.e("Lint", "Failed to analyze file", e);
            ((JavaCompilerService) mCompiler).destroy();
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
            List<VisitingDetector> list = mTreeTypeDetectors.get(AnnotationTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotation(annotationTree, unused);
                }
            }
            return null;
        }

        @Override
        public Void visitVariable(VariableTree variableTree, Void unused) {
            List<VisitingDetector> list = mTreeTypeDetectors.get(VariableTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitVariable(variableTree, unused);
                }
            }
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree methodInvocationTree, Void unused) {
            List<VisitingDetector> list = mTreeTypeDetectors.get(MethodInvocationTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitMethodInvocation(methodInvocationTree, unused);
                }
            }
            return super.visitMethodInvocation(methodInvocationTree, unused);
        }

        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            List<VisitingDetector> list = mTreeTypeDetectors.get(MethodTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitMethod(methodTree, unused);
                }
            }
            return super.visitMethod(methodTree, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree identifierTree, Void unused) {
            List<VisitingDetector> list = mTreeTypeDetectors.get(IdentifierTree.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitIdentifier(identifierTree, unused);
                }
            }
            return super.visitIdentifier(identifierTree, unused);
        }
    }

    /**
     * Performs common AST searches for method calls and R-type-field references.
     * Note that this is a specialized form of the {@link DispatchVisitor}.
     */
    private class DelegatingJavaVisitor extends DispatchVisitor {
        private final JavaContext mContext;
        private final boolean mVisitResources;
        private final boolean mVisitMethods;
        private final boolean mVisitConstructors;

        public DelegatingJavaVisitor(JavaContext context) {
            mContext = context;

            mVisitMethods = !mMethodDetectors.isEmpty();
            mVisitConstructors = false; //!mConstructorDetectors.isEmpty();
            mVisitResources = false; //!mResourceFieldDetectors.isEmpty();
        }

        @Override
        public Void visitIdentifier(IdentifierTree identifierTree, Void unused) {
            return super.visitIdentifier(identifierTree, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree memberSelectTree, Void unused) {
            if (mVisitResources) {
                // TODO: Complete
            }
            return super.visitMemberSelect(memberSelectTree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (mVisitMethods) {
                String methodName = JavaContext.getMethodName(node);
                List<VisitingDetector> list = mMethodDetectors.get(methodName);
                if (list != null) {
                    for (VisitingDetector v : list) {
                        v.getJavaScanner().visitMethod(mContext, v.getVisitor(), node);
                    }
                }
            }
            return super.visitMethodInvocation(node, unused);
        }
    }
}
