package com.tyron.completion.java.rewrite;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.google.common.collect.ImmutableMap;

import org.openjdk.javax.lang.model.element.TypeParameterElement;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.ParseTask;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.provider.ScopeHelper;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.JavaParserTypesUtil;
import com.tyron.completion.java.util.JavaParserUtil;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.type.TypeMirror;

public class IntroduceLocalVariable implements Rewrite {

    private static final Pattern DIGITS_PATTERN = Pattern.compile("^(.+?)(\\d+)$");

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
        try (CompilerContainer container = compiler.compile(file)) {
            return container.get(task -> {
                List<TextEdit> edits = new ArrayList<>();
                Range range = new Range(position, position);
                Type variableType = EditHelper.printType(type, true);
                if (variableType.isTypeParameter()) {
                    NodeList<ClassOrInterfaceType> typeBound =
                            variableType.asTypeParameter().getTypeBound();
                    Optional<ClassOrInterfaceType> first = typeBound.getFirst();
                    if (first.isPresent()) {
                        variableType = first.get();
                    }
                }
                String variableName = ActionUtil.guessNameFromMethodName(methodName);
                if (variableName == null) {
                    variableName = ActionUtil.guessNameFromType(type);
                }
                if (variableName == null) {
                    variableName = "variable";
                }
                while (containsVariableAtScope(variableName, task)) {
                    variableName = getVariableName(variableName);
                }
                String typeName = JavaParserTypesUtil.getName(variableType, name -> false);
                TextEdit edit = new TextEdit(range, typeName + " " + variableName + " = ");
                edits.add(edit);

                if (!type.getKind().isPrimitive()) {
                    List<String> classes = JavaParserUtil.getClassNames(variableType);
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

    private boolean containsVariableAtScope(String name, CompileTask parse) {
        TreePath scan = new FindCurrentPath(parse.task).scan(parse.root(), position + 1);
        if (scan == null) {
            return false;
        }
        Scope scope = Trees.instance(parse.task).getScope(scan);
        Iterable<? extends Element> localElements = scope.getLocalElements();
        for (Element element : localElements) {
            if (name.contentEquals(element.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private String getVariableName(String name) {
        Matcher matcher = DIGITS_PATTERN.matcher(name);
        if (matcher.matches()) {
            String variableName = matcher.group(1);
            String stringNumber = matcher.group(2);
            if (stringNumber == null) {
                stringNumber = "0";
            }
            int number = Integer.parseInt(stringNumber) + 1;
            return variableName + number;
        }
        return name + "1";
    }
}
