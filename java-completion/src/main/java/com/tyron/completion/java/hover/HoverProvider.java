package com.tyron.completion.java.hover;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.provider.FindHelper;
import com.tyron.completion.java.compiler.ParseTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.JavaFileObject;

public class HoverProvider {

    final CompilerProvider compiler;

    public static final List<String> NOT_SUPPORTED = Collections.emptyList();

    public HoverProvider(CompilerProvider provider) {
        compiler = provider;
    }

    public List<String> hover(Path file, int offset) {
        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
            Element element = new FindHoverElement(task.task).scan(task.root(), (long) offset);
            if (element == null) {
                return NOT_SUPPORTED;
            }
            List<String> list = new ArrayList<>();
            String code = printType(element);
            list.add(code);
            String docs = docs(task, element);
            if (!docs.isEmpty()) {
                list.add(docs);
            }
            return list;
        });
    }


    public String docs(CompileTask task, Element element) {
        if (element instanceof TypeElement) {
            TypeElement type = (TypeElement) element;
            String className = type.getQualifiedName().toString();
            Optional<JavaFileObject> file = compiler.findAnywhere(className);
            if (!file.isPresent()) return "";
            ParseTask parse = compiler.parse(file.get());
            Tree tree = FindHelper.findType(parse, className);
            return docs(parse, tree);
        } else if (element.getKind() == ElementKind.FIELD) {
            VariableElement field = (VariableElement) element;
            TypeElement type = (TypeElement) field.getEnclosingElement();
            String className = type.getQualifiedName().toString();
            Optional<JavaFileObject> file = compiler.findAnywhere(className);
            if (!file.isPresent()) return "";
            ParseTask parse = compiler.parse(file.get());
            Tree tree = FindHelper.findType(parse, className);
            return docs(parse, tree);
        } else if (element instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) element;
            TypeElement type = (TypeElement) method.getEnclosingElement();
            String className = type.getQualifiedName().toString();
            String methodName = method.getSimpleName().toString();
            String[] erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
            Optional<JavaFileObject> file = compiler.findAnywhere(className);
            if (!file.isPresent()) return "";
            ParseTask parse = compiler.parse(file.get());
            Tree tree = FindHelper.findMethod(parse, className, methodName, erasedParameterTypes);
            return docs(parse, tree);
        } else {
            return "";
        }
    }

    private String docs(ParseTask task, Tree tree) {
        TreePath path = Trees.instance(task.task).getPath(task.root, tree);
        DocCommentTree docTree = DocTrees.instance(task.task).getDocCommentTree(path);
        if (docTree == null) return "";
        //TODO: format this
        return docTree.toString();
    }

    private String printType(Element e) {
        if (e instanceof ExecutableElement) {
            ExecutableElement m = (ExecutableElement) e;
            return ShortTypePrinter.DEFAULT.printMethod(m);
        } else if (e instanceof VariableElement) {
            VariableElement v = (VariableElement) e;
            return ShortTypePrinter.DEFAULT.print(v.asType()) + " " + v;
        } else if (e instanceof TypeElement) {
            TypeElement t = (TypeElement) e;
            StringJoiner lines = new StringJoiner("\n");
            lines.add(hoverTypeDeclaration(t) + " {");
            for (Element member : t.getEnclosedElements()) {
                // TODO check accessibility
                if (member instanceof ExecutableElement || member instanceof VariableElement) {
                    lines.add("  " + printType(member) + ";");
                } else if (member instanceof TypeElement) {
                    lines.add("  " + hoverTypeDeclaration((TypeElement) member) + " { /* removed " +
                            "*/ }");
                }
            }
            lines.add("}");
            return lines.toString();
        } else {
            return e.toString();
        }
    }

    private String hoverTypeDeclaration(TypeElement t) {
        StringBuilder result = new StringBuilder();
        switch (t.getKind()) {
            case ANNOTATION_TYPE:
                result.append("@interface");
                break;
            case INTERFACE:
                result.append("interface");
                break;
            case CLASS:
                result.append("class");
                break;
            case ENUM:
                result.append("enum");
                break;
            default:
                result.append("_");
        }
        result.append(" ").append(ShortTypePrinter.DEFAULT.print(t.asType()));
        String superType = ShortTypePrinter.DEFAULT.print(t.getSuperclass());
        switch (superType) {
            case "Object":
            case "none":
                break;
            default:
                result.append(" extends ").append(superType);
        }
        return result.toString();
    }
}
