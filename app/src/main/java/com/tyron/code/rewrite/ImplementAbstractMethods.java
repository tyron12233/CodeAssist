package com.tyron.code.rewrite;

import android.util.Log;

import com.google.common.base.Strings;
import com.tyron.completion.CompileTask;
import com.tyron.completion.CompilerProvider;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.ParseTask;
import com.tyron.completion.provider.FindHelper;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.javax.lang.model.util.Types;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.util.Trees;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

public class ImplementAbstractMethods implements Rewrite {

    private final String mClassName;

    public ImplementAbstractMethods(String className) {
        mClassName = className;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        if (!compiler.isReady()) {
            return Collections.emptyMap();
        }

        Path file = compiler.findTypeDeclaration(mClassName);
        if (file == JavaCompilerService.NOT_FOUND) {
            return Collections.emptyMap();
        }

        StringJoiner insertText = new StringJoiner("\n");
        try (CompileTask task = compiler.compile(file)) {
            Elements elements = task.task.getElements();
            Types types = task.task.getTypes();
            Trees trees = Trees.instance(task.task);
            TypeElement thisClass = elements.getTypeElement(mClassName);
            DeclaredType thisType = (DeclaredType) thisClass.asType();
            ClassTree thisTree = trees.getTree(thisClass);
            int indent = EditHelper.indent(task.task, task.root(), thisTree) + 4;
            for (Element member : elements.getAllMembers(thisClass)) {
                if (member.getKind() == ElementKind.METHOD && member.getModifiers().contains(Modifier.ABSTRACT)) {
                    ExecutableElement method = (ExecutableElement) member;
                    MethodTree source = findSource(compiler, task, method);
                    if (source == null) {
                        Log.w(getClass().getSimpleName(), "Unable to find source for " + method);
                        continue;
                    }
                    ExecutableType parameterizedType = (ExecutableType) types.asMemberOf(thisType, method);
                    String text = EditHelper.printMethod(method, parameterizedType, source);
                    text = text.replaceAll("\n", "\n" + Strings.repeat(" ", indent));
                    insertText.add(text);
                }
            }

            Position insert = EditHelper.insertAtEndOfClass(task.task, task.root(), thisTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), insertText + "\n")};
            return Collections.singletonMap(file, edits);
        }
    }

    private MethodTree findSource(CompilerProvider compiler, CompileTask task, ExecutableElement method) {
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
