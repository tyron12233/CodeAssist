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

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class MemberReferenceCompletionProvider extends BaseCompletionProvider {

    public MemberReferenceCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path,
                         String partial, boolean endsWithParen) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        MemberReferenceTree select = (MemberReferenceTree) path.getLeaf();
        path = new TreePath(path, select.getQualifierExpression());
        Element element = trees.getElement(path);
        boolean isStatic = element instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);

        if (type instanceof ArrayType) {
            completeArrayMemberReference(builder, isStatic);
        } else if (type instanceof TypeVariable) {
            completeTypeVariableMemberReference(builder, task, scope, (TypeVariable) type, isStatic,
                                                partial);
        } else if (type instanceof DeclaredType) {
            completeDeclaredTypeMemberReference(builder, task, scope, (DeclaredType) type, isStatic,
                                                partial);
        }
    }

    private void completeArrayMemberReference(CompletionList.Builder builder, boolean isStatic) {
        if (isStatic) {
            builder.addItem(keyword("new"));
        }
    }

    private void completeTypeVariableMemberReference(CompletionList.Builder builder,
                                                     CompileTask task, Scope scope,
                                                     TypeVariable type, boolean isStatic,
                                                     String partial) {
        if (type.getUpperBound() instanceof DeclaredType) {
            completeDeclaredTypeMemberReference(builder, task, scope,
                                                (DeclaredType) type.getUpperBound(), isStatic,
                                                partial);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            completeTypeVariableMemberReference(builder, task, scope,
                                                (TypeVariable) type.getUpperBound(), isStatic,
                                                partial);
        }
    }

    private void completeDeclaredTypeMemberReference(CompletionList.Builder builder,
                                                     CompileTask task, Scope scope,
                                                     DeclaredType type, boolean isStatic,
                                                     String partial) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (FuzzySearch.partialRatio(String.valueOf(member.getSimpleName()), partial) < 70) {
                continue;
            }
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!trees.isAccessible(scope, member, type)) {
                continue;
            }
            if (!isStatic &&
                member.getModifiers()
                        .contains(Modifier.STATIC)) {
                continue;
            }
            if (member.getKind() == ElementKind.METHOD) {
                HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
                putMethod((ExecutableElement) member, methods);
                for (List<ExecutableElement> overloads : methods.values()) {
                    builder.addItems(method(task, overloads, false, true, type),
                                     JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                }
            } else {
                builder.addItem(item(member));
            }
        }
        if (isStatic) {
            builder.addItem(keyword("new"));
        }

        if (builder.getItemCount() > MAX_COMPLETION_ITEMS) {
            builder.incomplete();
        }
    }

}
