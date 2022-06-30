package com.tyron.completion.java.util;

import static com.tyron.completion.java.util.JavaParserTypesUtil.toType;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.ReceiverParameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import com.tyron.completion.java.rewrite.EditHelper;

import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeNoTypeArgumentsOnRhsError;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

public class JavaParserUtil {

    public static CompilationUnit toCompilationUnit(CompilationUnitTree tree) {
        CompilationUnit compilationUnit = new CompilationUnit();
        compilationUnit.setPackageDeclaration(toPackageDeclaration(tree.getPackage()));
        tree.getImports().forEach(importTree -> compilationUnit.addImport(toImportDeclaration(importTree)));
        compilationUnit.setTypes(tree.getTypeDecls().stream().map(JavaParserUtil::toClassOrInterfaceDeclaration).collect(NodeList.toNodeList()));
        return compilationUnit;
    }

    public static PackageDeclaration toPackageDeclaration(PackageTree tree) {
        PackageDeclaration declaration = new PackageDeclaration();
        declaration.setName(tree.getPackageName().toString());
        return declaration;
    }

    public static ImportDeclaration toImportDeclaration(ImportTree tree) {
        String name = tree.getQualifiedIdentifier().toString();
        boolean isAsterisk = name.endsWith("*");
        return new ImportDeclaration(name, tree.isStatic(), isAsterisk);
    }

    public static ClassOrInterfaceDeclaration toClassOrInterfaceDeclaration(Tree tree) {
        if (tree instanceof ClassTree) {
            return toClassOrInterfaceDeclaration((ClassTree) tree);
        }
        return null;
    }

    public static BlockStmt toBlockStatement(BlockTree tree) {
        BlockStmt blockStmt = new BlockStmt();
        blockStmt.setStatements(tree.getStatements().stream()
                .map(JavaParserUtil::toStatement)
                .collect(NodeList.toNodeList()));
        return blockStmt;
    }

    public static Statement toStatement(StatementTree tree) {
        if (tree instanceof ExpressionStatementTree) {
            return toExpressionStatement((ExpressionStatementTree) tree);
        }
        if (tree instanceof VariableTree) {
            return toVariableDeclarationExpression(((VariableTree) tree));
        }
        return StaticJavaParser.parseStatement(tree.toString());
    }

    public static ExpressionStmt toExpressionStatement(ExpressionStatementTree tree) {
        ExpressionStmt expressionStmt = new ExpressionStmt();
        expressionStmt.setExpression(toExpression(tree.getExpression()));
        return expressionStmt;
    }

    public static Expression toExpression(ExpressionTree tree) {
        if (tree instanceof MethodInvocationTree) {
            return toMethodCallExpression((MethodInvocationTree) tree);
        }
        if (tree instanceof MemberSelectTree) {
            return toFieldAccessExpression((MemberSelectTree) tree);
        }
        if (tree instanceof IdentifierTree) {
            return toNameExpr(((IdentifierTree) tree));
        }
        if (tree instanceof LiteralTree) {
            return toLiteralExpression(((LiteralTree) tree));
        }
        if (tree instanceof AssignmentTree) {
            return toAssignExpression(((AssignmentTree) tree));
        }
        if (tree instanceof ErroneousTree) {
            ErroneousTree erroneousTree = (ErroneousTree) tree;
            if (!erroneousTree.getErrorTrees().isEmpty()) {
                Tree errorTree = erroneousTree.getErrorTrees().get(0);
                return toExpression((ExpressionTree) errorTree);
            }
        }
        return null;
    }

    private static AssignExpr toAssignExpression(AssignmentTree tree) {
        AssignExpr assignExpr = new AssignExpr();
        assignExpr.setTarget(toExpression(tree.getVariable()));
        assignExpr.setValue(toExpression(tree.getExpression()));
        return assignExpr;
    }

    public static ExpressionStmt toVariableDeclarationExpression(VariableTree tree) {
        VariableDeclarationExpr expr = new VariableDeclarationExpr();
        expr.setModifiers(tree.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));

        VariableDeclarator declarator = new VariableDeclarator();
        declarator.setName(tree.getName().toString());
        declarator.setInitializer(toExpression(tree.getInitializer()));
        declarator.setType(toType(tree.getType()));
        expr.addVariable(declarator);

        ExpressionStmt stmt = new ExpressionStmt();
        stmt.setExpression(expr);
        return stmt;
    }

    public static NameExpr toNameExpr(IdentifierTree tree) {
        NameExpr nameExpr = new NameExpr();
        nameExpr.setName(tree.getName().toString());
        return nameExpr;
    }

    public static LiteralExpr toLiteralExpression(LiteralTree tree) {
        Object value = tree.getValue();
        if (value instanceof String) {
            return new StringLiteralExpr((String) value);
        }
        if (value instanceof Boolean) {
            return new BooleanLiteralExpr((Boolean) value);
        }
        if (value instanceof Integer) {
            return new IntegerLiteralExpr(String.valueOf(value));
        }
        if (value instanceof Character) {
            return new CharLiteralExpr((Character) value);
        }
        if (value instanceof Long) {
            return new LongLiteralExpr((Long) value);
        }
        if (value instanceof Double) {
            return new DoubleLiteralExpr((Double) value);
        }
        return null;
    }

    public static MethodCallExpr toMethodCallExpression(MethodInvocationTree tree) {
        MethodCallExpr expr = new MethodCallExpr();
        if (tree.getMethodSelect() instanceof MemberSelectTree) {
            MemberSelectTree methodSelect = (MemberSelectTree) tree.getMethodSelect();
            expr.setScope(toExpression(methodSelect.getExpression()));
            expr.setName(methodSelect.getIdentifier().toString());
        }
        expr.setArguments(tree.getArguments().stream()
                .map(JavaParserUtil::toExpression)
                .collect(NodeList.toNodeList()));
        expr.setTypeArguments(tree.getTypeArguments().stream()
                .map(JavaParserTypesUtil::toType)
                .collect(NodeList.toNodeList()));
        if (tree.getMethodSelect() instanceof IdentifierTree) {
            expr.setName(toNameExpr((IdentifierTree) tree.getMethodSelect()).getName());
        }
        return expr;
    }

    public static FieldAccessExpr toFieldAccessExpression(MemberSelectTree tree) {
        FieldAccessExpr fieldAccessExpr = new FieldAccessExpr();
        fieldAccessExpr.setName(tree.getIdentifier().toString());
        fieldAccessExpr.setScope(toExpression(tree.getExpression()));
        return fieldAccessExpr;
    }

    public static ClassOrInterfaceDeclaration toClassOrInterfaceDeclaration(ClassTree tree) {
        ClassOrInterfaceDeclaration declaration = new ClassOrInterfaceDeclaration();
        declaration.setName(tree.getSimpleName().toString());
        declaration.setExtendedTypes(NodeList.nodeList(JavaParserTypesUtil.toClassOrInterfaceType(tree.getExtendsClause())));
        declaration.setTypeParameters(tree.getTypeParameters().stream()
                .map(JavaParserUtil::toTypeParameter)
                .collect(NodeList.toNodeList()));
        declaration.setTypeParameters(tree.getTypeParameters().stream()
                .map(JavaParserUtil::toTypeParameter)
                .collect(NodeList.toNodeList()));
        declaration.setImplementedTypes(tree.getImplementsClause().stream()
                .map(JavaParserTypesUtil::toClassOrInterfaceType)
                .collect(NodeList.toNodeList()));
        declaration.setModifiers(tree.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));
        declaration.setMembers(tree.getMembers().stream()
                .map(JavaParserUtil::toBodyDeclaration)
                .collect(NodeList.toNodeList()));
        return declaration;
    }

    public static BodyDeclaration<?> toBodyDeclaration(Tree tree) {
        if (tree instanceof MethodTree) {
            return toMethodDeclaration(((MethodTree) tree), null);
        }
        if (tree instanceof VariableTree) {
            return toFieldDeclaration((VariableTree) tree);
        }
        return null;
    }

    public static FieldDeclaration toFieldDeclaration(VariableTree tree) {
        FieldDeclaration declaration = new FieldDeclaration();
        declaration.setModifiers(tree.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));

        VariableDeclarator declarator = new VariableDeclarator();
        declarator.setName(tree.getName().toString());

        Expression initializer = toExpression(tree.getInitializer());
        if (initializer != null) {
            declarator.setInitializer(initializer);
        }
        Type type = toType(tree.getType());
        if (type != null) {
            declarator.setType(type);
        }
        declaration.addVariable(declarator);

        return declaration;
    }

    public static MethodDeclaration toMethodDeclaration(MethodTree method, ExecutableType type) {
        MethodDeclaration methodDeclaration = new MethodDeclaration();
        methodDeclaration.setAnnotations(method.getModifiers().getAnnotations().stream()
                .map(JavaParserUtil::toAnnotation)
                .collect(NodeList.toNodeList()));
        methodDeclaration.setName(method.getName().toString());

        Type returnType;
        if (type != null) {
            returnType = toType(type.getReturnType());
        } else {
            returnType = toType(method.getReturnType());
        }

        if (returnType != null) {
            methodDeclaration.setType(getTypeWithoutBounds(returnType));
        }

        methodDeclaration.setModifiers(method.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));
        methodDeclaration.setParameters(method.getParameters().stream()
                .map(JavaParserUtil::toParameter)
                .peek(parameter -> {
                    Type firstType = getTypeWithoutBounds(parameter.getType());
                    parameter.setType(firstType);
                })
                .collect(NodeList.toNodeList()));
        methodDeclaration.setTypeParameters(method.getTypeParameters().stream()
                .map(it -> toType(((Tree) it)))
                .filter(Objects::nonNull)
                .map(type1 -> type1 != null ? type1.toTypeParameter() : Optional.<TypeParameter>empty())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(NodeList.toNodeList()));
        if (method.getBody() != null) {
            methodDeclaration.setBody(toBlockStatement(method.getBody()));
        }
        if (method.getReceiverParameter() != null) {
            methodDeclaration.setReceiverParameter(toReceiverParameter(method.getReceiverParameter()));
        }
        return methodDeclaration;
    }

    public static AnnotationExpr toAnnotation(AnnotationTree tree) {
        if (tree.getArguments().isEmpty()) {
            MarkerAnnotationExpr expr = new MarkerAnnotationExpr();
            expr.setName(toType(tree.getAnnotationType()).toString());
            return expr;
        }
        if (tree.getArguments().size() == 1) {
            SingleMemberAnnotationExpr expr = new SingleMemberAnnotationExpr();
            expr.setName(toType(tree.getAnnotationType()).toString());
            expr.setMemberValue(toExpression(tree.getArguments().get(0)));
            return expr;
        }
        NormalAnnotationExpr expr = new NormalAnnotationExpr();
        expr.setName(toType(tree.getAnnotationType()).toString());
        expr.setPairs(tree.getArguments().stream()
                .map(arg -> {
                    if (arg instanceof AssignmentTree) {
                        AssignExpr assignExpr = toAssignExpression((AssignmentTree) arg);
                        MemberValuePair pair = new MemberValuePair();
                        pair.setName(assignExpr.getTarget().toString());
                        pair.setValue(assignExpr.getValue());
                        return pair;
                    }
                    // TODO: Handle erroneous trees
                    return null;
                })
                .collect(NodeList.toNodeList()));
        return expr;
    }


    public static Parameter toParameter(VariableTree tree) {
        Parameter parameter = new Parameter();
        parameter.setType(toType(tree.getType()));
        parameter.setModifiers(tree.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));
        parameter.setName(tree.getName().toString());
        return parameter;
    }

    /**
     * Convert a parameter into {@link Parameter} object. This method is called from
     * source files, giving their accurate names.
     */
    public static Parameter toParameter(TypeMirror type, VariableTree name) {
        Parameter parameter = new Parameter();
        parameter.setType(EditHelper.printType(type));
        parameter.setName(name.getName().toString());
        parameter.setModifiers(name.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));
        parameter.setName(name.getName().toString());
        return parameter;
    }

    public static TypeParameter toTypeParameter(TypeParameterTree type) {
        return StaticJavaParser.parseTypeParameter(type.toString());
    }

    public static ReceiverParameter toReceiverParameter(VariableTree parameter) {
        ReceiverParameter receiverParameter = new ReceiverParameter();
        receiverParameter.setName(parameter.getName().toString());
        receiverParameter.setType(toType(parameter.getType()));
        return receiverParameter;
    }










    public static MethodDeclaration toMethodDeclaration(ExecutableElement method, ExecutableType type) {
        MethodDeclaration methodDeclaration = new MethodDeclaration();

        Type returnType;
        if (type != null) {
            returnType = toType(type.getReturnType());
        } else {
            returnType = toType(method.getReturnType());
        }

        if (returnType != null) {
            methodDeclaration.setType(getTypeWithoutBounds(returnType));
        }


        methodDeclaration.setDefault(method.isDefault());
        methodDeclaration.setName(method.getSimpleName().toString());
        methodDeclaration.setModifiers(method.getModifiers().stream()
                .map(modifier -> Modifier.Keyword.valueOf(modifier.name()))
                .toArray(Modifier.Keyword[]::new));
        methodDeclaration.setParameters(IntStream.range(0, method.getParameters().size())
                .mapToObj(i -> toParameter(type.getParameterTypes().get(i), method.getParameters().get(i)))
                .peek(parameter -> {
                    Type firstType = getTypeWithoutBounds(parameter.getType());
                    parameter.setType(firstType);
                })
                .collect(NodeList.toNodeList()));
        methodDeclaration.setTypeParameters(type.getTypeVariables().stream()
                .map(it -> toType(((TypeMirror) it)))
                .filter(Objects::nonNull)
                .map(type1 -> type1 != null ? type1.toTypeParameter() : Optional.<TypeParameter>empty())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(NodeList.toNodeList()));
        return methodDeclaration;
    }

    public static Type getFirstArrayType(Type type) {
        if (type.isTypeParameter()) {
            TypeParameter typeParameter = type.asTypeParameter();
            if (typeParameter.getTypeBound().isNonEmpty()) {
                Optional<ClassOrInterfaceType> first = typeParameter.getTypeBound().getFirst();
                if (first.isPresent()) {
                    return new ArrayType(first.get());
                }
            }
        }
        return type;
    }

    public static Type getFirstType(Type type) {
        if (type.isTypeParameter()) {
            TypeParameter typeParameter = type.asTypeParameter();
            if (typeParameter.getTypeBound().isNonEmpty()) {
                Optional<ClassOrInterfaceType> first = typeParameter.getTypeBound().getFirst();
                if (first.isPresent()) {
                    return first.get();
                }
            }
        }
        if (type.isClassOrInterfaceType()) {
            Optional<NodeList<Type>> typeArguments =
                    type.asClassOrInterfaceType().getTypeArguments();
            if (typeArguments.isPresent()) {
                if (typeArguments.get().isNonEmpty()) {
                    Optional<Type> first = typeArguments.get().getFirst();
                    if (first.isPresent()) {
                        if (first.get().isTypeParameter()) {
                            NodeList<ClassOrInterfaceType> typeBound =
                                    first.get().asTypeParameter().getTypeBound();
                            if (typeBound.isNonEmpty()) {
                                Optional<ClassOrInterfaceType> first1 = typeBound.getFirst();
                                if (first1.isPresent()) {
                                    type.asClassOrInterfaceType().setTypeArguments(first1.get());
                                    return type;
                                }
                            }
                        }
                    }
                }
            }
        }
        return type;
    }

    public static Type getTypeWithoutBounds(Type type) {
        if (type.isArrayType() && !type.asArrayType().getComponentType().isTypeParameter()) {
            return type;
        }
        if (!type.isArrayType() && !type.isTypeParameter()) {
            return type;
        }
        if (type instanceof NodeWithSimpleName) {
            return new ClassOrInterfaceType(((NodeWithSimpleName<?>) type).getNameAsString());
        }
        if (type.isArrayType()) {
            return new ArrayType(getTypeWithoutBounds(type.asArrayType().getComponentType()));
        }
        return type;
    }


    public static Modifier toModifier(javax.lang.model.element.Modifier modifier) {
        return new Modifier(Modifier.Keyword.valueOf(modifier.name()));
    }

    /**
     * Convert a parameter into {@link Parameter} object. This method is called from
     * compiled class files, giving inaccurate parameter names
     */
    public static Parameter toParameter(TypeMirror type, VariableElement name) {
        Parameter parameter = new Parameter();
        parameter.setType(EditHelper.printType(type));
        if (parameter.getType().isArrayType()) {
            if (((com.sun.tools.javac.code.Type.ArrayType) type).isVarargs()) {
                parameter.setType(parameter.getType().asArrayType().getComponentType());
                parameter.setVarArgs(true);
            }
        }
        parameter.setName(name.getSimpleName().toString());
        parameter.setModifiers(name.getModifiers().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));
        parameter.setName(name.getSimpleName().toString());
        return parameter;
    }

    public static TypeParameter toTypeParameter(TypeParameterElement type) {
        return StaticJavaParser.parseTypeParameter(type.toString());
    }

    public static TypeParameter toTypeParameter(TypeVariable typeVariable) {
        return StaticJavaParser.parseTypeParameter(typeVariable.toString());
    }

    public static List<String> getClassNames(Type type) {
        List<String> classNames = new ArrayList<>();
        if (type.isClassOrInterfaceType()) {
            classNames.add(type.asClassOrInterfaceType().getName().asString());
        }
        if (type.isWildcardType()) {
            WildcardType wildcardType = type.asWildcardType();
            wildcardType.getExtendedType().ifPresent(t -> classNames.addAll(getClassNames(t)));
            wildcardType.getSuperType().ifPresent(t -> classNames.addAll(getClassNames(t)));
        }
        if (type.isArrayType()) {
            classNames.addAll(getClassNames(type.asArrayType().getComponentType()));
        }
        if (type.isIntersectionType()) {
            type.asIntersectionType().getElements().stream()
                    .map(JavaParserUtil::getClassNames)
                    .forEach(classNames::addAll);
        }
        return classNames;
    }


    /**
     * Print a node declaration into its string representation
     * @param node node to print
     * @param delegate callback to whether a class name should be printed as fully qualified names
     * @return String representation of the method declaration properly formatted
     */
    public static String prettyPrint(Node node, JavaParserTypesUtil.NeedFqnDelegate delegate) {
        PrinterConfiguration configuration = new DefaultPrinterConfiguration();
        JavaPrettyPrinterVisitor visitor =  new JavaPrettyPrinterVisitor(configuration) {
            @Override
            public void visit(SimpleName n, Void arg) {
                printOrphanCommentsBeforeThisChildNode(n);
                printComment(n.getComment(), arg);

                String identifier = n.getIdentifier();
                if (delegate.needsFqn(identifier)) {
                    printer.print(identifier);
                } else {
                    printer.print(ActionUtil.getSimpleName(identifier));
                }
            }


            @Override
            public void visit(Name n, Void arg) {
                super.visit(n, arg);
            }
        };
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter(t -> visitor, configuration);
        return prettyPrinter.print(node);
    }
}
