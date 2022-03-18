package com.tyron.completion.java.rewrite;

import static com.tyron.completion.java.util.ActionUtil.containsVariableAtScope;
import static com.tyron.completion.java.util.ActionUtil.getVariableName;

import com.github.javaparser.ast.type.Type;
import com.google.common.collect.ImmutableMap;

import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.ElementUtil;
import com.tyron.completion.java.util.JavaParserTypesUtil;
import com.tyron.completion.java.util.JavaParserUtil;
import com.tyron.completion.java.util.PrintHelper;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

public class IntroduceLocalVariable implements JavaRewrite {

    private final Path file;
    private final String methodName;
    private final TypeMirror type;
    private final long position;

    public IntroduceLocalVariable(Path file, String methodName, TypeMirror type, long position) {
        this.file = file;
        this.methodName = methodName;
        this.type = type;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
            List<TextEdit> edits = new ArrayList<>();
            Range range = new Range(position, position);
            String variableType = PrintHelper.printType(type, false);
            String variableName = ActionUtil.guessNameFromMethodName(methodName);
            if (variableName == null) {
                variableName = ActionUtil.guessNameFromType(type);
            }
            if (variableName == null) {
                variableName = "variable";
            }
            while (containsVariableAtScope(variableName, position, task)) {
                variableName = getVariableName(variableName);
            }

            TextEdit edit = new TextEdit(range, variableType + " " + variableName + " = ");
            edits.add(edit);

            if (!type.getKind().isPrimitive()) {
                List<String> classes = ElementUtil.getAllClasses(type);
                for (String aClass : classes) {
                    if (!ActionUtil.hasImport(task.root(), aClass)) {
                        AddImport addImport = new AddImport(file.toFile(), aClass);
                        Map<Path, TextEdit[]> rewrite = addImport.rewrite(compiler);
                        TextEdit[] imports = rewrite.get(file);
                        if (imports != null) {
                            Collections.addAll(edits, imports);
                        }
                    }
                }
            }
            return ImmutableMap.of(file, edits.toArray(new TextEdit[0]));
        });
    }
}
