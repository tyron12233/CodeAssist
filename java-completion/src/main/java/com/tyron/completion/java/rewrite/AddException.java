package com.tyron.completion.java.rewrite;

import com.google.common.collect.ImmutableMap;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.provider.FindHelper;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.tree.LineMap;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.Trees;

import java.nio.file.Path;
import java.util.Map;

public class AddException implements JavaRewrite {

    private final String className;
    private final String methodName;
    private final String[] erasedParameterTypes;
    private String exceptionType;

    public AddException(String className, String methodName, String[] erasedParameterTypes,
                        String exceptionType) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.exceptionType = exceptionType;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        Path file = compiler.findTypeDeclaration(className);
        if (file == CompilerProvider.NOT_FOUND) {
            return CANCELLED;
        }
        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
            Trees trees = Trees.instance(task.task);
            ExecutableElement methodElement = FindHelper.findMethod(task, className, methodName,
                    erasedParameterTypes);
            MethodTree methodTree = trees.getTree(methodElement);
            SourcePositions pos = trees.getSourcePositions();
            LineMap lines = task.root().getLineMap();
            long startBody = pos.getStartPosition(task.root(), methodTree.getBody());
            String packageName = "";
            String simpleName = exceptionType;
            int lastDot = simpleName.lastIndexOf('.');
            if (lastDot != -1) {
                packageName = exceptionType.substring(0, lastDot);
                simpleName = exceptionType.substring(lastDot + 1);
            }
            String insertText;
            if (methodTree.getThrows().isEmpty()) {
                insertText = " throws " + simpleName + " ";
            } else {
                insertText = ", " + simpleName + " ";
            }
            TextEdit insertThrows = new TextEdit(new Range(startBody - 1, startBody - 1),
                    insertText);
            return ImmutableMap.of(file, new TextEdit[]{insertThrows});
        });
    }
}
