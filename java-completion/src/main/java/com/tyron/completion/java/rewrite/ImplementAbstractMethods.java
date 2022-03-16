package com.tyron.completion.java.rewrite;

import androidx.annotation.Nullable;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.google.common.base.Strings;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.FindNewTypeDeclarationAt;
import com.tyron.completion.java.FindTypeDeclarationAt;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.java.provider.FindHelper;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.JavaParserUtil;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.util.JCDiagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

public class ImplementAbstractMethods implements JavaRewrite {

    private static final String TAG = ImplementAbstractMethods.class.getSimpleName();

    private final String mClassName;
    private final String mClassFile;
    private final long mPosition;

    public ImplementAbstractMethods(String className, String classFile, long lineStart) {
        if (className.startsWith("<anonymous")) {
            className = className.substring("<anonymous ".length(), className.length() - 1);
        }
        mClassName = className;
        mClassFile = classFile;
        mPosition = 0;
    }

    public ImplementAbstractMethods(JCDiagnostic diagnostic) {
        Object[] args = diagnostic.getArgs();
        String className = args[0].toString();

        if (!className.contains("<anonymous")) {
            mClassName = className;
            mClassFile = className;
            mPosition = 0;
        } else {
            className = className.substring("<anonymous ".length(), className.length() - 1);
            className = className.substring(0, className.indexOf('$'));
            mClassFile = className;
            mClassName = args[2].toString();
            mPosition = diagnostic.getStartPosition();
        }
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        Path file = compiler.findTypeDeclaration(mClassFile);
        if (file == JavaCompilerService.NOT_FOUND) {
            return Collections.emptyMap();
        }

        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
            return rewriteInternal(compiler, task, file);
        });
    }

    private Map<Path, TextEdit[]> rewriteInternal(CompilerProvider compiler, CompileTask task, Path file) {
        Elements elements = task.task.getElements();
        Types types = task.task.getTypes();
        Trees trees = Trees.instance(task.task);
        List<TextEdit> edits = new ArrayList<>();
        List<TextEdit> importEdits = new ArrayList<>();
        Set<String> typesToImport = new HashSet<>();

        TypeElement thisClass = elements.getTypeElement(mClassName);
        ClassTree thisTree = getClassTree(task, file);
        if (thisTree == null) {
            thisTree = trees.getTree(thisClass);
        }
        CompilationUnitTree root = task.root(file);
        if (root == null) {
            return CANCELLED;
        }

        TreePath path = trees.getPath(root, thisTree);
        Element element = trees.getElement(path);
        DeclaredType thisType = (DeclaredType) element.asType();

        StringJoiner insertText = new StringJoiner("\n");

        int indent = EditHelper.indent(task.task, task.root(), thisTree) + 1;
        String tabs = Strings.repeat("\t", indent);

        for (Element member : elements.getAllMembers(thisClass)) {
            if (member.getKind() == ElementKind.METHOD && member.getModifiers().contains(Modifier.ABSTRACT)) {
                ExecutableElement method = (ExecutableElement) member;
                ExecutableType parameterizedType =
                        (ExecutableType) types.asMemberOf(thisType, method);
                typesToImport.addAll(ActionUtil.getTypesToImport(parameterizedType));


                MethodTree source = findSource(compiler, task, method);
                MethodDeclaration methodDeclaration;
                if (source != null) {
                    methodDeclaration = EditHelper.printMethod(method, parameterizedType,
                            source);
                } else {
                    methodDeclaration = EditHelper.printMethod(method, parameterizedType,
                            method);
                }

                String text = JavaParserUtil.prettyPrint(methodDeclaration, className -> false);
                text = tabs + text.replace("\n", "\n" + tabs);
                if (insertText.length() != 0) {
                    text = "\n" + text;
                }

                insertText.add(text);
            }
        }

        Position insert = EditHelper.insertAtEndOfClass(task.task, task.root(), thisTree);
        insert.line -= 1;
        edits.add(new TextEdit(new Range(insert, insert), insertText + "\n"));
        edits.addAll(importEdits);

        for (String type : typesToImport) {
            String fqn = ActionUtil.removeDiamond(type);
            if (!ActionUtil.hasImport(task.root(), fqn)) {
                JavaRewrite addImport = new AddImport(file.toFile(), fqn);
                Map<Path, TextEdit[]> rewrite = addImport.rewrite(compiler);
                TextEdit[] textEdits = rewrite.get(file);
                if (textEdits != null) {
                    Collections.addAll(edits, textEdits);
                }
            }
        }

        return Collections.singletonMap(file, edits.toArray(new TextEdit[0]));
    }

    @Nullable
    private ClassTree getClassTree(CompileTask task, Path file) {
        ClassTree thisTree = null;
        CompilationUnitTree root = task.root(file);
        if (root == null) {
            return null;
        }
        if (mPosition != 0) {
            thisTree = new FindTypeDeclarationAt(task.task).scan(root, mPosition);
        }
        if (thisTree == null) {
            thisTree = new FindNewTypeDeclarationAt(task.task, root).scan(root,
                    mPosition);
        }
        return thisTree;
    }

    private MethodTree findSource(CompilerProvider compiler, CompileTask task,
                                  ExecutableElement method) {
        TypeElement superClass = (TypeElement) method.getEnclosingElement();
        String superClassName = superClass.getQualifiedName().toString();
        String methodName = method.getSimpleName().toString();
        String[] erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
        Optional<JavaFileObject> sourceFile = compiler.findAnywhere(superClassName);
        if (!sourceFile.isPresent()) return null;
        ParseTask parse = compiler.parse(sourceFile.get());
        return FindHelper.findMethod(parse, superClassName, methodName, erasedParameterTypes);
    }
}
