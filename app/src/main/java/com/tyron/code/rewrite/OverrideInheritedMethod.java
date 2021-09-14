package com.tyron.code.rewrite;

import com.google.common.base.Strings;
import com.tyron.code.completion.CompileTask;
import com.tyron.code.completion.CompilerProvider;
import com.tyron.code.completion.FindTypeDeclarationAt;
import com.tyron.code.completion.ParseTask;
import com.tyron.completion.provider.FindHelper;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.util.Types;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

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
        TextEdit[] edits = {new TextEdit(new Range(insertPoint, insertPoint), insertText)};
        return Collections.singletonMap(file, edits);
    }

    private String insertText(CompilerProvider compiler) {
        try (CompileTask task = compiler.compile(file)) {
            Types types = task.task.getTypes();
            Trees trees = Trees.instance(task.task);
            ExecutableElement superMethod = FindHelper.findMethod(task, superClassName, methodName, erasedParameterTypes);
            ClassTree thisTree = new FindTypeDeclarationAt(task.task).scan(task.root(), (long) insertPosition);
            TreePath thisPath = trees.getPath(task.root(), thisTree);
            TypeElement thisClass = (TypeElement) trees.getElement(thisPath);
            ExecutableType parameterizedType = (ExecutableType) types.asMemberOf((DeclaredType) thisClass.asType(), superMethod);
            int indent = EditHelper.indent(task.task, task.root(), thisTree) + 4;
            Optional<JavaFileObject> sourceFile = compiler.findAnywhere(superClassName);
            if (sourceFile.isPresent()) {
                ParseTask parse = compiler.parse(sourceFile.get());
                MethodTree source = FindHelper.findMethod(parse, superClassName, methodName, erasedParameterTypes);
                String text = EditHelper.printMethod(superMethod, parameterizedType, source);
                text = text.replaceAll("\n", "\n" + Strings.repeat(" ", indent));
                text = text + "\n\n";
                return text;
            } else {
                String text = EditHelper.printMethod(superMethod, parameterizedType, null);
                text = text.replaceAll("\n", "\n" + Strings.repeat(" ", indent));
                text = text + "\n\n";
                return text;
            }
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
        for (Tree member : parent.getMembers()) {
            long start = pos.getStartPosition(task.root, member);
            if (start > insertPosition) {
                int line = (int) task.root.getLineMap().getLineNumber(start);
                return new Position(line - 1, 0);
            }
        }
        return Position.NONE;
    }
}
