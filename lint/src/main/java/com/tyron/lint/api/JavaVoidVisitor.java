package com.tyron.lint.api;

import org.openjdk.source.tree.AnnotatedTypeTree;
import org.openjdk.source.tree.AnnotationTree;
import org.openjdk.source.tree.ArrayAccessTree;
import org.openjdk.source.tree.ArrayTypeTree;
import org.openjdk.source.tree.AssertTree;
import org.openjdk.source.tree.AssignmentTree;
import org.openjdk.source.tree.BinaryTree;
import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.BreakTree;
import org.openjdk.source.tree.CaseTree;
import org.openjdk.source.tree.CatchTree;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.CompoundAssignmentTree;
import org.openjdk.source.tree.ConditionalExpressionTree;
import org.openjdk.source.tree.ContinueTree;
import org.openjdk.source.tree.DoWhileLoopTree;
import org.openjdk.source.tree.EmptyStatementTree;
import org.openjdk.source.tree.EnhancedForLoopTree;
import org.openjdk.source.tree.ErroneousTree;
import org.openjdk.source.tree.ExportsTree;
import org.openjdk.source.tree.ExpressionStatementTree;
import org.openjdk.source.tree.ForLoopTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.IfTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.InstanceOfTree;
import org.openjdk.source.tree.IntersectionTypeTree;
import org.openjdk.source.tree.LabeledStatementTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.LiteralTree;
import org.openjdk.source.tree.MemberReferenceTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.ModifiersTree;
import org.openjdk.source.tree.ModuleTree;
import org.openjdk.source.tree.NewArrayTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.OpensTree;
import org.openjdk.source.tree.PackageTree;
import org.openjdk.source.tree.ParameterizedTypeTree;
import org.openjdk.source.tree.ParenthesizedTree;
import org.openjdk.source.tree.PrimitiveTypeTree;
import org.openjdk.source.tree.ProvidesTree;
import org.openjdk.source.tree.RequiresTree;
import org.openjdk.source.tree.ReturnTree;
import org.openjdk.source.tree.SwitchTree;
import org.openjdk.source.tree.SynchronizedTree;
import org.openjdk.source.tree.ThrowTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.TreeVisitor;
import org.openjdk.source.tree.TryTree;
import org.openjdk.source.tree.TypeCastTree;
import org.openjdk.source.tree.TypeParameterTree;
import org.openjdk.source.tree.UnaryTree;
import org.openjdk.source.tree.UnionTypeTree;
import org.openjdk.source.tree.UsesTree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.tree.WhileLoopTree;
import org.openjdk.source.tree.WildcardTree;
import org.openjdk.source.util.TreeScanner;

public class JavaVoidVisitor implements TreeVisitor<Void, Void> {

    @Override
    public Void visitAnnotatedType(AnnotatedTypeTree annotatedTypeTree, Void unused) {
        return null;
    }

    @Override
    public Void visitAnnotation(AnnotationTree annotationTree, Void unused) {
        return null;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree methodInvocationTree, Void unused) {
        return null;
    }

    @Override
    public Void visitAssert(AssertTree assertTree, Void unused) {
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentTree assignmentTree, Void unused) {
        return null;
    }

    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree compoundAssignmentTree, Void unused) {
        return null;
    }

    @Override
    public Void visitBinary(BinaryTree binaryTree, Void unused) {
        return null;
    }

    @Override
    public Void visitBlock(BlockTree blockTree, Void unused) {
        return null;
    }

    @Override
    public Void visitBreak(BreakTree breakTree, Void unused) {
        return null;
    }

    @Override
    public Void visitCase(CaseTree caseTree, Void unused) {
        return null;
    }

    @Override
    public Void visitCatch(CatchTree catchTree, Void unused) {
        return null;
    }

    @Override
    public Void visitClass(ClassTree classTree, Void unused) {
        return null;
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree conditionalExpressionTree, Void unused) {
        return null;
    }

    @Override
    public Void visitContinue(ContinueTree continueTree, Void unused) {
        return null;
    }

    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree doWhileLoopTree, Void unused) {
        return null;
    }

    @Override
    public Void visitErroneous(ErroneousTree erroneousTree, Void unused) {
        return null;
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementTree expressionStatementTree, Void unused) {
        return null;
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree enhancedForLoopTree, Void unused) {
        return null;
    }

    @Override
    public Void visitForLoop(ForLoopTree forLoopTree, Void unused) {
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierTree identifierTree, Void unused) {
        return null;
    }

    @Override
    public Void visitIf(IfTree ifTree, Void unused) {
        return null;
    }

    @Override
    public Void visitImport(ImportTree importTree, Void unused) {
        return null;
    }

    @Override
    public Void visitArrayAccess(ArrayAccessTree arrayAccessTree, Void unused) {
        return null;
    }

    @Override
    public Void visitLabeledStatement(LabeledStatementTree labeledStatementTree, Void unused) {
        return null;
    }

    @Override
    public Void visitLiteral(LiteralTree literalTree, Void unused) {
        return null;
    }

    @Override
    public Void visitMethod(MethodTree methodTree, Void unused) {
        return null;
    }

    @Override
    public Void visitModifiers(ModifiersTree modifiersTree, Void unused) {
        return null;
    }

    @Override
    public Void visitNewArray(NewArrayTree newArrayTree, Void unused) {
        return null;
    }

    @Override
    public Void visitNewClass(NewClassTree newClassTree, Void unused) {
        return null;
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree lambdaExpressionTree, Void unused) {
        return null;
    }

    @Override
    public Void visitPackage(PackageTree packageTree, Void unused) {
        return null;
    }

    @Override
    public Void visitParenthesized(ParenthesizedTree parenthesizedTree, Void unused) {
        return null;
    }

    @Override
    public Void visitReturn(ReturnTree returnTree, Void unused) {
        return null;
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree memberSelectTree, Void unused) {
        return null;
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree memberReferenceTree, Void unused) {
        return null;
    }

    @Override
    public Void visitEmptyStatement(EmptyStatementTree emptyStatementTree, Void unused) {
        return null;
    }

    @Override
    public Void visitSwitch(SwitchTree switchTree, Void unused) {
        return null;
    }

    @Override
    public Void visitSynchronized(SynchronizedTree synchronizedTree, Void unused) {
        return null;
    }

    @Override
    public Void visitThrow(ThrowTree throwTree, Void unused) {
        return null;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree compilationUnitTree, Void unused) {
        return null;
    }

    @Override
    public Void visitTry(TryTree tryTree, Void unused) {
        return null;
    }

    @Override
    public Void visitParameterizedType(ParameterizedTypeTree parameterizedTypeTree, Void unused) {
        return null;
    }

    @Override
    public Void visitUnionType(UnionTypeTree unionTypeTree, Void unused) {
        return null;
    }

    @Override
    public Void visitIntersectionType(IntersectionTypeTree intersectionTypeTree, Void unused) {
        return null;
    }

    @Override
    public Void visitArrayType(ArrayTypeTree arrayTypeTree, Void unused) {
        return null;
    }

    @Override
    public Void visitTypeCast(TypeCastTree typeCastTree, Void unused) {
        return null;
    }

    @Override
    public Void visitPrimitiveType(PrimitiveTypeTree primitiveTypeTree, Void unused) {
        return null;
    }

    @Override
    public Void visitTypeParameter(TypeParameterTree typeParameterTree, Void unused) {
        return null;
    }

    @Override
    public Void visitInstanceOf(InstanceOfTree instanceOfTree, Void unused) {
        return null;
    }

    @Override
    public Void visitUnary(UnaryTree unaryTree, Void unused) {
        return null;
    }

    @Override
    public Void visitVariable(VariableTree variableTree, Void unused) {
        return null;
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree whileLoopTree, Void unused) {
        return null;
    }

    @Override
    public Void visitWildcard(WildcardTree wildcardTree, Void unused) {
        return null;
    }

    @Override
    public Void visitModule(ModuleTree moduleTree, Void unused) {
        return null;
    }

    @Override
    public Void visitExports(ExportsTree exportsTree, Void unused) {
        return null;
    }

    @Override
    public Void visitOpens(OpensTree opensTree, Void unused) {
        return null;
    }

    @Override
    public Void visitProvides(ProvidesTree providesTree, Void unused) {
        return null;
    }

    @Override
    public Void visitRequires(RequiresTree requiresTree, Void unused) {
        return null;
    }

    @Override
    public Void visitUses(UsesTree usesTree, Void unused) {
        return null;
    }

    @Override
    public Void visitOther(Tree tree, Void unused) {
        return null;
    }
}
