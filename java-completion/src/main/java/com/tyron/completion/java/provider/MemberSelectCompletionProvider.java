package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.java.util.CompletionItemFactory.keyword;
import static com.tyron.completion.java.util.CompletionItemFactory.method;
import static com.tyron.completion.java.util.ElementUtil.isEnclosingClass;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
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
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class MemberSelectCompletionProvider extends BaseCompletionProvider {

    public MemberSelectCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path,
                         String partial, boolean endsWithParen) {
        checkCanceled();

        MemberSelectTree select = (MemberSelectTree) path.getLeaf();
        path = new TreePath(path, select.getExpression());
        Trees trees = task.getTrees();
        Element element;
        try {
            element = trees.getElement(path);
        } catch (Throwable t) {
            element = null;
        }
        boolean isStatic = element instanceof TypeElement;

        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);

        if (type instanceof ArrayType) {
            completeArrayMemberSelect(builder, isStatic);
        } else if (type instanceof TypeVariable) {
            completeTypeVariableMemberSelect(builder, task, scope, (TypeVariable) type, isStatic,
                                                    partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            completeDeclaredTypeMemberSelect(builder, task, scope, (DeclaredType) type, isStatic,
                                                                                       partial, endsWithParen);
        } else if (type instanceof PrimitiveType) {
            if (path.getLeaf()
                        .getKind() != Tree.Kind.METHOD_INVOCATION) {
                completePrimitiveMemberSelect(builder, task, scope, (PrimitiveType) type, isStatic,
                                                     partial, endsWithParen);
            }
        }
    }


    private void completePrimitiveMemberSelect(CompletionList.Builder builder, CompileTask task, Scope scope,
                                                         PrimitiveType type, boolean isStatic,
                                                         String partial, boolean endsWithParen) {
        checkCanceled();
        builder.addItem(keyword("class"));
    }
    private void completeArrayMemberSelect(CompletionList.Builder builder, boolean isStatic) {
        checkCanceled();

        if (!isStatic) {
            builder.addItem(keyword("length"));
        }
    }

    private void completeTypeVariableMemberSelect(CompletionList.Builder builder,
                                                  CompileTask task, Scope scope,
                                                  TypeVariable type, boolean isStatic,
                                                  String partial, boolean endsWithParen) {
        checkCanceled();

        if (type.getUpperBound() instanceof DeclaredType) {
            completeDeclaredTypeMemberSelect(builder, task, scope,
                                                    (DeclaredType) type.getUpperBound(), isStatic,
                                                    partial, endsWithParen);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            completeTypeVariableMemberSelect(builder, task, scope,
                                                    (TypeVariable) type.getUpperBound(), isStatic,
                                                    partial, endsWithParen);
        }
    }

    private void completeDeclaredTypeMemberSelect(CompletionList.Builder builder, CompileTask task, Scope scope, DeclaredType type, boolean isStatic, String partial, boolean endsWithParen) {
        checkCanceled();
        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();

        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.task.getElements()
                .getAllMembers(typeElement)) {
            checkCanceled();

            if (builder.getItemCount() >= Completions.MAX_COMPLETION_ITEMS) {
                builder.incomplete();
                break;
            }
            if (member.getKind() == ElementKind.CONSTRUCTOR) {
                continue;
            }
            if (FuzzySearch.tokenSetPartialRatio(String.valueOf(member.getSimpleName()), partial) < 70 &&
                !partial.endsWith(".") &&
                !partial.isEmpty()) {
                continue;
            }
            if (!trees.isAccessible(scope, member, type)) {
                continue;
            }
            if (isStatic !=
                member.getModifiers()
                        .contains(Modifier.STATIC)) {
                continue;
            }
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                builder.addItem(item(member));
            }
        }

        for (List<ExecutableElement> overloads : methods.values()) {
            builder.addItems(method(task, overloads, endsWithParen, false, type),
                             JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
        }

        if (isStatic) {
            if (StringSearch.matchesPartialName("class", partial)) {
                builder.addItem(keyword("class"));
            }
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            if (StringSearch.matchesPartialName("this", partial)) {
                builder.addItem(keyword("this"));
            }
            if (StringSearch.matchesPartialName("super", partial)) {
                builder.addItem(keyword("super"));
            }
        }
    }

    public static void putMethod(ExecutableElement method,
                                 Map<String, List<ExecutableElement>> methods) {
        String name = method.getSimpleName()
                .toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        List<ExecutableElement> elements = methods.get(name);
        if (elements != null) {
            elements.add(method);
        }
    }
}
