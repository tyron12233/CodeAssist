package com.tyron.completion.java.provider;

import static com.tyron.completion.java.provider.Completions.MAX_COMPLETION_ITEMS;
import static com.tyron.completion.java.provider.MemberSelectCompletionProvider.putMethod;
import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.java.util.CompletionItemFactory.keyword;
import static com.tyron.completion.java.util.CompletionItemFactory.method;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.ArrayType;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.type.TypeVariable;
import org.openjdk.source.tree.MemberReferenceTree;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class MemberReferenceCompletionProvider extends BaseCompletionProvider {

    public MemberReferenceCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        MemberReferenceTree select = (MemberReferenceTree) path.getLeaf();
        path = new TreePath(path, select.getQualifierExpression());
        Element element = trees.getElement(path);
        boolean isStatic = element instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);

        if (type instanceof ArrayType) {
            return completeArrayMemberReference(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberReference(task,
                    scope, (TypeVariable) type, isStatic, partial);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(task,
                    scope, (DeclaredType) type, isStatic, partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeArrayMemberReference(boolean isStatic) {
        if (isStatic) {
            CompletionList list = new CompletionList();
            list.items.add(keyword("new"));
            return list;
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeTypeVariableMemberReference(CompileTask task, Scope scope,
                                                               TypeVariable type,
                                                               boolean isStatic, String partial) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(task, scope,
                    (DeclaredType) type.getUpperBound(), isStatic, partial);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberReference(task, scope,
                    (TypeVariable) type.getUpperBound(), isStatic, partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberReference(CompileTask task, Scope scope,
                                                               DeclaredType type,
                                                               boolean isStatic, String partial) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();
        List<CompletionItem> list = new ArrayList<>();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (FuzzySearch.partialRatio(String.valueOf(member.getSimpleName()), partial) < 70) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (!isStatic && member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
                putMethod((ExecutableElement) member, methods);
                for (List<ExecutableElement> overloads : methods.values()) {
                    list.addAll(method(task, overloads, false, true, type));
                }
            } else {
                list.add(item(member));
            }
        }
        if (isStatic) {
            list.add(keyword("new"));
        }

        CompletionList comp = new CompletionList();
        comp.isIncomplete = !(list.size() > MAX_COMPLETION_ITEMS);
        comp.items = list;
        return comp;
    }

}
