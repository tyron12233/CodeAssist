package com.tyron.completion.java.rewrite;

import com.google.common.base.Strings;
import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.FindTypeDeclarationAt;
import com.tyron.completion.java.ParseTask;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.java.provider.FindHelper;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class OverrideInheritedMethod implements Rewrite {

    final String superClassName, methodName;
    final String[] erasedParameterTypes;
    final Path file;
    final int insertPosition;

    public OverrideInheritedMethod(
            String superClassName, String methodName, String[] erasedParameterTypes, Path file, int insertPosition) {
        this.superClassName = superClassName;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.file = file;
        this.insertPosition = insertPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        Position insertPoint = insertNearCursor(compiler);
        String insertText = insertText(compiler);
        if (insertText == null) {
            return Rewrite.CANCELLED;
        }
        TextEdit[] edits = {new TextEdit(new Range(insertPoint, insertPoint), insertText, true)};
        return Collections.singletonMap(file, edits);
    }

    private String insertText(CompilerProvider compiler) {
        try (CompileTask task = compiler.compile(file)) {
            Types types = task.task.getTypes();
            Trees trees = Trees.instance(task.task);
            ExecutableElement superMethod = FindHelper.findMethod(task, superClassName, methodName, erasedParameterTypes);
            if (superMethod == null) {
                return null;
            }

            ClassTree thisTree = new FindTypeDeclarationAt(task.task).scan(task.root(), (long) insertPosition);
            TreePath thisPath = trees.getPath(task.root(), thisTree);
            TypeElement thisClass = (TypeElement) trees.getElement(thisPath);
            ExecutableType parameterizedType = (ExecutableType) types.asMemberOf((DeclaredType) thisClass.asType(), superMethod);
            int indent = EditHelper.indent(task.task, task.root(), thisTree) + 4;
            Optional<JavaFileObject> sourceFile = compiler.findAnywhere(superClassName);
            String text;
            if (sourceFile.isPresent()) {
                ParseTask parse = compiler.parse(sourceFile.get());
                MethodTree source = FindHelper.findMethod(parse, superClassName, methodName, erasedParameterTypes);
                if (source == null) {
                    text = EditHelper.printMethod(superMethod, parameterizedType, superMethod);
                } else {
                    text = EditHelper.printMethod(superMethod, parameterizedType, source);
                }
            } else {
                text = EditHelper.printMethod(superMethod, parameterizedType, superMethod);
            }

            int tabCount = indent / 4;

            String tabs = Strings.repeat("\t", tabCount);

            text = tabs + text.replace("\n", "\n" + tabs)
                    + "\n\n";
            return text;
        }
    }

    private Position insertNearCursor(CompilerProvider compiler) {
        ParseTask task = compiler.parse(file);
        ClassTree parent = new FindTypeDeclarationAt(task.task).scan(task.root, (long) insertPosition);
        Position next = nextMember(task, parent);
        if (next != Position.NONE) {
            return next;
        }
        return EditHelper.insertAtEndOfClass(task.task, task.root, parent);
    }

    private Position nextMember(ParseTask task, ClassTree parent) {
        SourcePositions pos = Trees.instance(task.task).getSourcePositions();
        if (parent != null) {
            for (Tree member : parent.getMembers()) {
                long start = pos.getStartPosition(task.root, member);
                if (start > insertPosition) {
                    int line = (int) task.root.getLineMap().getLineNumber(start);
                    return new Position(line - 1, 0);
                }
            }
        }
        return Position.NONE;
    }
}
