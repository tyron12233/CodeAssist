package com.tyron.lint.checks;

import android.util.Log;

import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.tyron.lint.api.Category;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Implementation;
import com.tyron.lint.api.Issue;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;
import com.tyron.lint.api.Scope;
import com.tyron.lint.api.Severity;

import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class JavaPerformanceDetector extends Detector implements Detector.JavaScanner {

    private static final Implementation IMPLEMENTATION = new Implementation(
            JavaPerformanceDetector.class,
            Scope.JAVA_FILE_SCOPE);

    public static final Issue PAINT_ALLOC = Issue.create(
            "DrawAllocation",
            "Memory allocations within drawing code",

            "You should avoid allocating objects during a drawing or layout operation. These " +
                    "are called frequently, so a smooth UI can be interrupted by garbage collection " +
                    "pauses caused by the object allocations.\n" +
                    "\n" +
                    "The way this is generally handled is to allocate the needed objects up front " +
                    "and to reuse them for each drawing operation.\n" +
                    "\n" +
                    "Some methods allocate memory on your behalf (such as `Bitmap.create`), and these " +
                    "should be handled in the same way.",

            Category.PERFORMANCE,
            9,
            Severity.WARNING,
            IMPLEMENTATION);

    static final String ON_MEASURE = "onMeasure";                           //$NON-NLS-1$
    static final String ON_DRAW = "onDraw";                                 //$NON-NLS-1$
    static final String ON_LAYOUT = "onLayout";                             //$NON-NLS-1$
    private static final String INTEGER = "Integer";                        //$NON-NLS-1$
    private static final String BOOLEAN = "Boolean";                        //$NON-NLS-1$
    private static final String BYTE = "Byte";                              //$NON-NLS-1$
    private static final String LONG = "Long";                              //$NON-NLS-1$
    private static final String CHARACTER = "Character";                    //$NON-NLS-1$
    private static final String DOUBLE = "Double";                          //$NON-NLS-1$
    private static final String FLOAT = "Float";                            //$NON-NLS-1$
    private static final String HASH_MAP = "HashMap";                       //$NON-NLS-1$
    private static final String SPARSE_ARRAY = "SparseArray";               //$NON-NLS-1$
    private static final String CANVAS = "Canvas";                          //$NON-NLS-1$
    private static final String LAYOUT = "layout";

    @Override
    public JavaVoidVisitor getVisitor(JavaContext context) {
        return new Visitor(context);
    }

    @Override
    public List<Class<? extends Tree>> getApplicableTypes() {
        return Arrays.asList(
                MethodTree.class,
                MethodInvocationTree.class
        );
    }

    private static class Visitor extends JavaVoidVisitor {

        private final JavaContext mContext;
        /** Whether allocations should be "flagged" in the current method */
        private boolean mFlagAllocations;

        public Visitor(JavaContext context) {
            mContext = context;
        }

        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            mFlagAllocations = isBlockedAllocationMethod(methodTree);

            return super.visitMethod(methodTree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            Trees trees = Trees.instance(mContext.getCompileTask().task);
            TreePath parent = trees.getPath(mContext.getCompilationUnit(), node).getParentPath();
            if (mFlagAllocations && !(parent.getLeaf() instanceof  ThrowTree)) {
                Tree method = node;
                while (method != null) {
                    if (method instanceof MethodTree) {
                        break;
                    }
                    method = trees.getPath(mContext.getCompilationUnit(), method).getParentPath().getLeaf();
                }

                if (method != null && isBlockedAllocationMethod((MethodTree) method)
                        && !isLazilyInitialized(node)) {
                    reportAllocation(node);
                }
            }
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (mFlagAllocations && node.getMethodSelect() != null) {
                String methodName;
                if (node.getMethodSelect() instanceof MemberSelectTree) {
                    methodName = ((MemberSelectTree) node.getMethodSelect()).getIdentifier().toString();
                } else {
                    methodName = ((IdentifierTree) node.getMethodSelect()).getName().toString();
                }
                if (methodName.equals("createBitmap")                              //$NON-NLS-1$
                        || methodName.equals("createScaledBitmap")) {
                    String operand = ((IdentifierTree) ((MemberSelectTree) node.getMethodSelect()).getExpression()).getName().toString();
                    if (operand.equals("Bitmap")                                   //$NON-NLS-1$
                            || operand.equals("android.graphics.Bitmap")) {        //$NON-NLS-1$
                        if (!isLazilyInitialized(node)) {
                            reportAllocation(node);
                        }
                    }
                } else if (methodName.startsWith("decode")) {
                    String operand = ((IdentifierTree) ((MemberSelectTree) node.getMethodSelect()).getExpression()).getName().toString();
                    if (operand.equals("BitmapFactory")
                        || operand.equals("android.graphics.BitmapFactory")) {
                        if (isLazilyInitialized(node)) {
                            reportAllocation(node);
                        }
                    }
                } else if (methodName.equals("getClipBounds")) {
                    if (node.getArguments().isEmpty()) {
                        mContext.report(PAINT_ALLOC, node, mContext.getLocation(node), "Avoid object allocations during draw operations: Use " +
                                "`Canvas.getClipBounds(Rect)` instead of `Canvas.getClipBounds()` " +
                                "which allocates a temporary `Rect`");
                    }
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        private void reportAllocation(Tree node) {
            mContext.report(PAINT_ALLOC, node, mContext.getLocation(node),
                    "Avoid object allocations during draw/layout operations (preallocate and " +
                            "reuse instead)");
        }

        private static boolean isBlockedAllocationMethod(MethodTree node) {
            return isOnDrawMethod(node) || isOnMeasureMethod(node) || isOnLayoutMethod(node);
        }

        private boolean isLazilyInitialized(Tree node) {
            TreePath curr = TreePath.getPath(mContext.getCompilationUnit(), node).getParentPath();
            while (curr != null) {
                if (curr.getLeaf() instanceof MethodTree) {
                    return false;
                } else if (curr.getLeaf() instanceof IfTree){
                    IfTree ifNode = (IfTree) curr.getLeaf();
                    List<String> assignments = new ArrayList<>();
                    AssignmentTracker visitor = new AssignmentTracker(assignments);
                    ifNode.accept(visitor, null);
                    if (!assignments.isEmpty()) {
                        List<String> references = new ArrayList<>();
                        addReferencedVariables(references, ifNode.getCondition());
                        SetView<String> intersection = Sets.intersection(
                                new HashSet<>(assignments),
                                new HashSet<>(references));
                        return intersection.isEmpty();
                    }
                }

                curr = curr.getParentPath();
            }
            return false;
        }

        private static void addReferencedVariables(Collection<String> variables, ExpressionTree expression) {
            if (expression instanceof BinaryTree) {
                addReferencedVariables(variables, ((BinaryTree) expression).getLeftOperand());
                addReferencedVariables(variables, ((BinaryTree) expression).getRightOperand());
            } else if (expression instanceof UnaryTree) {
                addReferencedVariables(variables, ((UnaryTree) expression).getExpression());
            } else if (expression instanceof VariableTree) {
                addReferencedVariables(variables, expression);
            } else if (expression instanceof MemberSelectTree) {
                addReferencedVariables(variables, ((MemberSelectTree) expression).getExpression());
            }
        }

        /**
         * Returns true if this method looks like it's overriding android.view.View's
         * {@code protected void onDraw(Canvas canvas)}
         */
        private static boolean isOnDrawMethod(MethodTree node) {
            if (ON_DRAW.contentEquals(node.getName())) {
                List<? extends VariableTree> parameters = node.getParameters();
                if (parameters != null && parameters.size() == 1) {
                    VariableTree arg0 = parameters.get(0);
                    IdentifierTree type = (IdentifierTree) arg0.getType();
                    return CANVAS.contentEquals(type.getName());
                }
            }
            return false;
        }


        /**
         * Returns true if this method looks like it's overriding
         * android.view.View's
         * {@code protected void onLayout(boolean changed, int left, int top,
         *      int right, int bottom)}
         */
        private static boolean isOnLayoutMethod(MethodTree node) {
            if (ON_LAYOUT.contentEquals(node.getName())) {
                List<? extends VariableTree> parameters = node.getParameters();
                if (parameters != null && parameters.size() == 5) {
                    Iterator<? extends VariableTree> iterator = parameters.iterator();
                    if (!iterator.hasNext()) {
                        return false;
                    }

                    // Ensure that the argument list matches boolean, int, int, int, int
                    Tree tree = iterator.next().getType();
                    if (!(tree instanceof PrimitiveTypeTree)) {
                        return false;
                    }
                    PrimitiveTypeTree type = (PrimitiveTypeTree) tree;
                    if (type.getPrimitiveTypeKind() != TypeKind.BOOLEAN || !iterator.hasNext()) {
                        return false;
                    }
                    for (int i = 0; i < 4; i++) {
                        type = (PrimitiveTypeTree) iterator.next().getType();
                        if (type.getPrimitiveTypeKind() != TypeKind.INT) {
                            return false;
                        }

                        if (!iterator.hasNext()) {
                            return i == 3;
                        }
                    }

                }
            }
            return false;
        }

        /**
         * Returns true if this method looks like it's overriding android.view.View's
         * {@code protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)}
         */
        private static boolean isOnMeasureMethod(MethodTree node) {
            if (ON_MEASURE.contentEquals(node.getName())) {
                List<? extends VariableTree> parameters = node.getParameters();
                if (parameters != null && parameters.size() == 2) {
                    for (VariableTree param : parameters) {
                        if (!(param.getType() instanceof PrimitiveTypeTree)) {
                            return false;
                        }
                        if (((PrimitiveTypeTree) param.getType()).getPrimitiveTypeKind() != TypeKind.INT) {
                                return false;
                        }
                    }
                    return true;
                }
            }
            return false;
        }
    }

    private static class AssignmentTracker extends JavaVoidVisitor {
        private final Collection<String> mVariables;

        public AssignmentTracker(Collection<String> variables) {
            mVariables = variables;
        }

        @Override
        public Void visitBinary(BinaryTree binaryTree, Void unused) {
            Log.d(null, "visit binary: " + binaryTree.getKind());
            if (binaryTree.getKind() == Tree.Kind.EQUAL_TO || binaryTree.getKind() == Tree.Kind.OR_ASSIGNMENT) {
                ExpressionTree left = binaryTree.getLeftOperand();
                String variable;
                if (left instanceof MemberSelectTree && ((MemberSelectTree) left).getIdentifier().contentEquals("this")) {
                    variable = ((MemberSelectTree) left).getIdentifier().toString();
                } else {
                    variable = left.toString();
                }
                mVariables.add(variable);
                Log.d(null, "adding variable: " + variable);
            }
            return super.visitBinary(binaryTree, unused);
        }
    }
}
