package com.tyron.completion.java.util;

import static com.tyron.completion.java.util.ElementUtil.simpleClassName;
import static com.tyron.completion.java.util.ElementUtil.simpleType;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import androidx.annotation.NonNull;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.insert.MethodInsertHandler;
import com.tyron.completion.java.provider.JavaSortCategory;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.DrawableKind;

import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.openjdk.javax.annotation.processing.Completion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class CompletionItemFactory {

    /**
     * Creates a completion item from a java psi element
     * @param element The psi element
     * @return the completion item instance
     */
    public static CompletionItem forPsiElement(PsiNamedElement element) {
        if (element instanceof PsiMethod) {
            return forPsiMethod(((PsiMethod) element));
        }
        return item(element.getName());
    }

    public static CompletionItem forPsiMethod(PsiMethod psiMethod) {
        CompletionItem item = new CompletionItem();
        item.label = getMethodLabel(psiMethod);
        item.detail = psiMethod.getReturnType().getPresentableText();
        item.commitText = psiMethod.getName();
        item.cursorOffset = item.commitText.length();
        item.iconKind = DrawableKind.Method;
        return item;
    }




    public static CompletionItem packageSnippet(Path file) {
        String name = "com.tyron.test";
        return snippetItem("package " + name, "package " + name + ";\n\n");
    }

    public static CompletionItem classSnippet(Path file) {
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".java".length());
        return snippetItem("class " + name, "class " + name + " {\n    $0\n}");
    }

    public static CompletionItem keyword(String keyword) {
        CompletionItem completionItem =
                CompletionItem.create(keyword, keyword, keyword, DrawableKind.Keyword);
        completionItem.setSortText(JavaSortCategory.KEYWORD.toString());
        return completionItem;
    }

    public static CompletionItem packageItem(String name) {
        return CompletionItem.create(name, "", name, DrawableKind.Package);
    }

    public static CompletionItem classItem(String className) {
        CompletionItem item = CompletionItem.create(simpleClassName(className),
                className, simpleClassName(className), DrawableKind.Class);
        item.data = className;
        item.action = CompletionItem.Kind.IMPORT;
        item.setSortText(JavaSortCategory.TO_IMPORT.toString());
        return item;
    }

    public static CompletionItem importClassItem(String className) {
        CompletionItem item = classItem(className);
        item.action = CompletionItem.Kind.NORMAL;;
        return item;
    }

    public static CompletionItem snippetItem(String label, String snippet) {
        CompletionItem item = new CompletionItem();
        item.label = label;
        item.commitText = snippet;
        item.cursorOffset = item.commitText.length();
        item.detail = "Snippet";
        item.iconKind = DrawableKind.Snippet;
        return item;
    }

    public static CompletionItem item(Element element) {
        CompletionItem item = new CompletionItem();
        item.label = element.getSimpleName().toString();
        item.detail = simpleType(element.asType());
        item.commitText = element.getSimpleName().toString();
        item.cursorOffset = item.commitText.length();
        item.iconKind = getKind(element);
        item.setInsertHandler(new DefaultInsertHandler(item));
        return item;
    }

    public static CompletionItem item(String element) {
        CompletionItem item = new CompletionItem();
        item.label = element;
        item.detail = "";
        item.commitText = element;
        item.cursorOffset = item.commitText.length();
        item.iconKind = DrawableKind.Snippet;
        item.setInsertHandler(new DefaultInsertHandler(item));
        return item;
    }

    private String getThrowsType(ExecutableElement e) {
        if (e.getThrownTypes() == null) {
            return "";
        }

        if (e.getThrownTypes().isEmpty()) {
            return "";
        }

        StringBuilder types = new StringBuilder();
        for (TypeMirror m : e.getThrownTypes()) {
            types.append((types.length() == 0) ? "" : ", ").append(simpleType(m));
        }

        return " throws " + types;
    }

    public static String getMethodLabel(@NonNull PsiMethod psiMethod) {
        String name = psiMethod.getName();
        String parameters = "";
        if (psiMethod.hasParameters()) {
            PsiParameterList parameterList = psiMethod.getParameterList();
            parameters = Arrays.stream(parameterList.getParameters())
                    .map(PsiParameter::getType)
                    .map(PsiType::getPresentableText)
                    .collect(Collectors.joining(", "));
        }
        return name + "(" + parameters + ")";
    }

    public static String getMethodLabel(ExecutableElement element, ExecutableType type) {
        String name = element.getSimpleName().toString();
        String params = PrintHelper.printParameters(type, element);
        return name + "(" + params + ")";
    }

    public static String getMethodLabel(MethodTree element, ExecutableType type) {
        String name = element.getName().toString();
        String params = PrintHelper.printParameters(type, element);
        return name + "(" + params + ")";
    }

    public static List<CompletionItem> method(CompileTask task, List<ExecutableElement> overloads,
                                        boolean endsWithParen, boolean methodRef,
                                        DeclaredType type) {
        checkCanceled();
        List<CompletionItem> items = new ArrayList<>();
        Types types = task.task.getTypes();
        for (ExecutableElement overload : overloads) {
            ExecutableType executableType = (ExecutableType) types.asMemberOf(type, overload);
            items.add(method(overload, endsWithParen, methodRef, executableType));
        }
        return items;
    }

    public static CompletionItem method(MethodTree first, boolean endsWithParen,
                                        boolean methodRef, ExecutableType type) {
        CompletionItem item = new CompletionItem();
        item.label = getMethodLabel(first, type);
        item.commitText = first.getName() + ((methodRef || endsWithParen) ? "" :
                "()");
        item.detail = type != null
                ? PrintHelper.printType(type.getReturnType())
                : ActionUtil.getSimpleName(first.getReturnType().toString());
        item.iconKind = DrawableKind.Method;
        item.cursorOffset = item.commitText.length();
        if (first.getParameters() != null && !first.getParameters().isEmpty()) {
            item.cursorOffset = item.commitText.length() -
                    ((methodRef || endsWithParen) ? 0 : 1);
        }
        item.setInsertHandler(new DefaultInsertHandler(item));
        return item;
    }

    public static CompletionItem method(ExecutableElement first, boolean endsWithParen,
                                  boolean methodRef, ExecutableType type) {
        CompletionItem item = new CompletionItem();
        item.label = getMethodLabel(first, type);
        item.commitText = first.getSimpleName().toString();
        item.detail = type != null
                ? PrintHelper.printType(type.getReturnType())
                : PrintHelper.printType(first.getReturnType());
        item.iconKind = DrawableKind.Method;
        item.cursorOffset = item.commitText.length();
        if (first.getParameters() != null && !first.getParameters().isEmpty()) {
            item.cursorOffset = item.commitText.length() -
                    ((methodRef || endsWithParen) ? 0 : 1);
        }
        item.setInsertHandler(new MethodInsertHandler(first, item, !methodRef));
        item.addFilterText(first.getSimpleName().toString());
        return item;
    }

    public static List<CompletionItem> method(CompileTask task, List<ExecutableElement> overloads,
                                        boolean endsWithParen, boolean methodRef,
                                        ExecutableType type) {
        checkCanceled();
        List<CompletionItem> items = new ArrayList<>();
        for (ExecutableElement first : overloads) {
            checkCanceled();
            items.add(method(first, endsWithParen, methodRef, type));
        }
        return items;
    }

    public static List<CompletionItem> overridableMethod(CompileTask task, TreePath parentPath,
                                                   List<ExecutableElement> overloads,
                                                   boolean endsWithParen) {
        checkCanceled();

        List<CompletionItem> items = new ArrayList<>(overloads.size());
        Types types = task.task.getTypes();
        Element parentElement = Trees.instance(task.task).getElement(parentPath);
        DeclaredType type = (DeclaredType) parentElement.asType();
        for (ExecutableElement element : overloads) {
            checkCanceled();

            Element enclosingElement = element.getEnclosingElement();
            if (!types.isAssignable(type, enclosingElement.asType())) {
                items.addAll(method(task, Collections.singletonList(element), endsWithParen,
                        false, type));
                continue;
            }

            ExecutableType executableType = (ExecutableType) types.asMemberOf(type, element);
            String text = PrintHelper.printMethod(element, executableType, element);

            CompletionItem item = new CompletionItem();
            item.label = getMethodLabel(element, executableType);
            item.detail = PrintHelper.printType(element.getReturnType());
            item.commitText = text;
            item.cursorOffset = item.commitText.length();
            item.iconKind = DrawableKind.Method;
            item.setInsertHandler(new DefaultInsertHandler(item));
            items.add(item);
        }
        return items;
    }

    private static DrawableKind getKind(Element element) {
        switch (element.getKind()) {
            case METHOD:
                return DrawableKind.Method;
            case CLASS:
                return DrawableKind.Class;
            case INTERFACE:
                return DrawableKind.Interface;
            case FIELD:
                return DrawableKind.Field;
            default:
                return DrawableKind.LocalVariable;
        }
    }
}
