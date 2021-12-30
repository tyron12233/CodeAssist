package com.tyron.completion.java.rewrite;

import android.util.Log;

import com.google.common.base.Strings;
import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.FindNewTypeDeclarationAt;
import com.tyron.completion.java.FindTypeDeclarationAt;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.ParseTask;
import com.tyron.completion.java.provider.FindHelper;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.javax.lang.model.util.Types;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.util.JCDiagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

public class ImplementAbstractMethods implements Rewrite {

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
        if (!compiler.isReady()) {
            Log.w(TAG, "Compiler is in use, returning empty map");
            return Collections.emptyMap();
        }

        Path file = compiler.findTypeDeclaration(mClassFile);
        if (file == JavaCompilerService.NOT_FOUND) {
            return Collections.emptyMap();
        }

        StringJoiner insertText = new StringJoiner("\n");
        try (CompileTask task = compiler.compile(file)) {
            Elements elements = task.task.getElements();
            Types types = task.task.getTypes();
            Trees trees = Trees.instance(task.task);
            TypeElement thisClass = elements.getTypeElement(mClassName);
            ClassTree thisTree = trees.getTree(thisClass);
            if (mPosition != 0) {
                thisTree = new FindTypeDeclarationAt(task.task).scan(task.root(), mPosition);
            }
            if (thisTree == null) {
                thisTree = new FindNewTypeDeclarationAt(task.task, task.root()).scan(task.root(),
                        mPosition);
            }
            TreePath path = trees.getPath(task.root(), thisTree);
            Element element = trees.getElement(path);
            DeclaredType thisType = (DeclaredType) element.asType();

            List<TypeMirror> typesToImport = new ArrayList<>();

            int indent = EditHelper.indent(task.task, task.root(), thisTree) + 4;

            for (Element member : elements.getAllMembers(thisClass)) {
                if (member.getKind() == ElementKind.METHOD && member.getModifiers().contains(Modifier.ABSTRACT)) {
                    ExecutableElement method = (ExecutableElement) member;
                    MethodTree source = findSource(compiler, task, method);
                    int tabCount = indent / 4;
                    String tabs = Strings.repeat("\t", tabCount);
                    ExecutableType parameterizedType = (ExecutableType) types.asMemberOf(thisType
                            , method);
                    String text;
                    if (source != null) {
                        text = EditHelper.printMethod(method, parameterizedType, source);
                    } else {
                        text = EditHelper.printMethod(method, parameterizedType, method);
                    }

                    if (method.getThrownTypes() != null) {
                        typesToImport.addAll(method.getThrownTypes());
                    }
                    for (VariableElement parameter : method.getParameters()) {
                        typesToImport.add(parameter.asType());
                    }

                    text = tabs + text.replace("\n", "\n" + tabs);
                    insertText.add(text);
                }
            }

            Position insert = EditHelper.insertAtEndOfClass(task.task, task.root(), thisTree);

            for (TypeMirror type : typesToImport) {
                String fqn = EditHelper.printType(type, true);
                System.out.println(fqn);
            }
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), insertText + "\n", true)};
            return Collections.singletonMap(file, edits);
        }
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
