package com.tyron.completion.java.rewrite;

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
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.util.JCDiagnostic;

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
        List<TextEdit> edits = new ArrayList<>();
        List<TextEdit> importEdits = new ArrayList<>();

        Path file = compiler.findTypeDeclaration(mClassFile);
        if (file == JavaCompilerService.NOT_FOUND) {
            return Collections.emptyMap();
        }

        StringJoiner insertText = new StringJoiner("\n");
        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
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

            Set<String> importedClasses = new HashSet<>();
            task.root().getImports().stream()
                    .map(ImportTree::getQualifiedIdentifier)
                    .map(Object::toString)
                    .forEach(importedClasses::add);
            Set<String> typesToImport = new HashSet<>();

            int indent = EditHelper.indent(task.task, task.root(), thisTree) + 1;
            String tabs = Strings.repeat("\t", indent);

            for (Element member : elements.getAllMembers(thisClass)) {
                if (member.getKind() == ElementKind.METHOD && member.getModifiers().contains(Modifier.ABSTRACT)) {
                    ExecutableElement method = (ExecutableElement) member;
                    MethodTree source = findSource(compiler, task, method);
                    ExecutableType parameterizedType = (ExecutableType) types.asMemberOf(thisType
                            , method);

                    typesToImport.addAll(ActionUtil.getTypesToImport(parameterizedType));


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
                    importedClasses.add(fqn);
                }
            }

            return Collections.singletonMap(file, edits.toArray(new TextEdit[0]));
        });
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
