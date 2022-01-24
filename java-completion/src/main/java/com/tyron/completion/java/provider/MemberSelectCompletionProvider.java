package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.java.util.CompletionItemFactory.keyword;
import static com.tyron.completion.java.util.CompletionItemFactory.method;
import static com.tyron.completion.java.util.ElementUtil.isEnclosingClass;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.ArrayType;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.PrimitiveType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.type.TypeVariable;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class MemberSelectCompletionProvider extends BaseCompletionProvider {

    public MemberSelectCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        checkCanceled();

        MemberSelectTree select = (MemberSelectTree) path.getLeaf();
        path = new TreePath(path, select.getExpression());
        Trees trees = Trees.instance(task.task);
        boolean isStatic = trees.getElement(path) instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);

        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope, (TypeVariable) type, isStatic,
                    partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope, (DeclaredType) type, isStatic,
                    partial, endsWithParen);
        } else if (type instanceof PrimitiveType) {
            return completePrimitiveMemberSelect(task, scope, (PrimitiveType) type, isStatic,
                    partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }


    private CompletionList completePrimitiveMemberSelect(CompileTask task, Scope scope,
                                                         PrimitiveType type, boolean isStatic,
                                                         String partial, boolean endsWithParen) {
        checkCanceled();

        CompletionList list = new CompletionList();
        list.items.add(keyword("class"));
        return list;
    }


    private CompletionList completeArrayMemberSelect(boolean isStatic) {
        checkCanceled();

        if (isStatic) {
            return new CompletionList();
        } else {
            CompletionList list = new CompletionList();
            list.items.add(keyword("length"));
            return list;
        }
    }

    private CompletionList completeTypeVariableMemberSelect(CompileTask task, Scope scope,
                                                            TypeVariable type, boolean isStatic,
                                                            String partial, boolean endsWithParen) {
        checkCanceled();

        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope,
                    (DeclaredType) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope,
                    (TypeVariable) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberSelect(CompileTask task, Scope scope,
                                                            DeclaredType type, boolean isStatic,
                                                            String partial, boolean endsWithParen) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();

        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (FuzzySearch.partialRatio(String.valueOf(member.getSimpleName()), partial) < 70 && !partial.endsWith(".") && !partial.isEmpty()) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }
        }

        for (List<ExecutableElement> overloads : methods.values()) {
            list.addAll(method(task, overloads, endsWithParen, false, type));
        }

        if (isStatic) {
            if (StringSearch.matchesPartialName("class", partial)) {
                list.add(keyword("class"));
            }
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            if (StringSearch.matchesPartialName("this", partial)) {
                list.add(keyword("this"));
            }
            if (StringSearch.matchesPartialName("super", partial)) {
                list.add(keyword("super"));
            }
        }

        CompletionList cl = new CompletionList();
        cl.items = list;
        return cl;
    }

    public static void putMethod(ExecutableElement method, Map<String, List<ExecutableElement>> methods) {
        String name = method.getSimpleName().toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        List<ExecutableElement> elements = methods.get(name);
        if (elements != null) {
            elements.add(method);
        }
    }
}
